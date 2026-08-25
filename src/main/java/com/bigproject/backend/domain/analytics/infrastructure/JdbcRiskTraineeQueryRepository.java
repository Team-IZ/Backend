package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcRiskTraineeQueryRepository implements RiskTraineeQueryRepository {

	// 위험 유형. INTV.INTERVIEW_CANDIDATE_REASON_REASON_CODE의 5개 값 중 단계 하락·지속 저점만 사용한다.
	// INVALID_ATTEMPT는 목록에서 뺀다. 무효 응시(CONFIRMED_INVALID)를 미집계로 분모에서 제외하므로
	// 그 교육생은 분모에 없고, 분자에만 남기면 분자가 분모를 벗어난다.
	// CONTRIBUTION_UNDERSTANDING_GAP·LOW_PARTICIPATION은 이 화면의 집계 대상이 아니다.
	private static final String RISK_REASON_CODES = "'STAGE_DECLINE', 'PERSISTENT_LOW'";

	// 집계 대상(분모) 조건. 미집계 3종(미응시·중단·무효 응시)을 모두 제외한다.
	// 분자도 이 조건을 함께 걸어 분자가 항상 분모의 부분집합이 되도록 한다.
	private static final String ELIGIBLE_CONDITION =
			"a.terminal_reason_code IS DISTINCT FROM 'NOT_ATTENDED' "
					+ "AND a.terminal_reason_code IS DISTINCT FROM 'SESSION_INCOMPLETE' "
					+ "AND a.validity_review_status <> 'CONFIRMED_INVALID'";

	/**
	 * 1차 회차 판정. 기수의 미니프로젝트 회차를 운영 순서로 다시 번호 매겨 1번인 회차를 가린다.
	 *
	 * <p>{@code project.sequence_no}를 그대로 쓰지 않는다. 그 값은 빅프로젝트를 포함한 기수 전체
	 * 운영 순서라 빅프로젝트가 앞에 끼면 첫 미니프로젝트가 1이 아니게 된다. 화면이 쓰는 축은
	 * 삭제되지 않은 미니프로젝트만 다시 번호 매긴 값이며 {@code mini_project_round_sequence_view}가
	 * 같은 식을 쓴다. 뷰를 조인하지 않고 식을 옮겨 적는 이유는 이 파일의 다른 질의와 마찬가지로
	 * 조인 경로를 한 눈에 보이게 두기 위해서다 — 두 곳이 갈리면 1차 판정이 화면과 어긋난다.
	 *
	 * <p>조회 범위(projectId·회차 범위)를 여기에 걸지 않는다. 4차만 조회해도 그 회차가 1차인지는
	 * 기수 전체를 봐야 정해진다.
	 */
	private static final String COHORT_ROUND_SEQUENCE_CTE = """
			cohort_round_sequence AS (
				SELECT
					par.assessment_round_id,
					DENSE_RANK() OVER (ORDER BY pj.sequence_no, pj.project_id) AS analysis_sequence_no
				FROM project_assessment_round par
				JOIN project pj ON pj.project_id = par.project_id AND pj.deleted_at IS NULL
				WHERE par.cohort_id = ?
					AND par.org_id = ?
					AND pj.project_category = ?
					AND par.deleted_at IS NULL
			)
			""";

	/**
	 * 분자 조건. 1차 회차에서만 관찰(NOT_APPLICABLE)을 위험으로 함께 센다.
	 *
	 * <p>1차는 비교할 직전 회차가 없어 단계 하락·지속 저점이 모두 {@code NOT_APPLICABLE}로 등재되고
	 * ({@code FIRST_MINI_PROJECT}·{@code INSUFFICIENT_LONGITUDINAL_HISTORY}) {@code MATCHED}가 하나도
	 * 나오지 않는다. 그대로 두면 게이트에 걸린 교육생이 면담 목록(MG-03)에는 `관찰`로 뜨는데 이 격자만
	 * 0%로 비어, 같은 회차를 두 화면이 다르게 말한다.
	 *
	 * <p>2차 이상은 종전대로 {@code MATCHED}만 센다. 1차를 미응시·중단한 교육생은 2차에서도 비교 이력이
	 * 없어 관찰로 등재되지만, 그 열의 기준은 건드리지 않는다.
	 */
	private static final String RISK_CONDITION =
			"(r.matched OR (a.is_first_round AND r.observed))";

	/** 위 분자 중 관찰로 잡힌 인원만. riskCount의 부분집합이라 화면이 구성을 드러낼 수 있다. */
	private static final String OBSERVED_CONDITION =
			"(a.is_first_round AND r.observed AND NOT r.matched)";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<CohortScope> findCohortScope(UUID cohortId) {
		return jdbcTemplate.query(
				"SELECT cohort_id, org_id FROM cohort WHERE cohort_id = ? AND deleted_at IS NULL",
				(rs, rowNum) -> new CohortScope(
						rs.getObject("cohort_id", UUID.class),
						rs.getObject("org_id", UUID.class)
				),
				cohortId
		).stream().findFirst();
	}

	@Override
	public boolean classroomBelongsToCohort(UUID classroomId, UUID cohortId, UUID organizationId) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM "class"
				WHERE class_id = ?
					AND cohort_id = ?
					AND org_id = ?
					AND deleted_at IS NULL
				""",
				Integer.class,
				classroomId,
				cohortId,
				organizationId
		);
		return count != null && count > 0;
	}

	@Override
	public boolean projectBelongsToCohort(
			UUID projectId,
			UUID cohortId,
			UUID organizationId,
			String projectCategory
	) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM project
				WHERE project_id = ?
					AND cohort_id = ?
					AND org_id = ?
					AND project_category = ?
					AND deleted_at IS NULL
				""",
				Integer.class,
				projectId,
				cohortId,
				organizationId,
				projectCategory
		);
		return count != null && count > 0;
	}

	/**
	 * 회차 열을 조회한다.
	 *
	 * 격자의 가로축은 project_assessment_round.round_no가 아니라 project.sequence_no다.
	 * 미니프로젝트는 프로젝트마다 이해도 확인 회차가 1건뿐이라 round_no가 늘 1이고, 기수의
	 * 차수 흐름은 프로젝트 순서에만 남는다. RoundCriteria의 from/to도 이 sequence_no 범위다.
	 * 열은 최근 프로젝트부터 내림차순으로 내려 화면이 최신 회차를 왼쪽에 먼저 그리게 한다.
	 *
	 * 집계 상태는 회차 생명주기가 아니라 발행된 리포트 유무로 판정한다.
	 * 화면이 '리포트가 발행되면 채워집니다'로 설명하므로 그 계약에 맞춘다.
	 * PLANNED만 회차 status에서 직접 읽어 '시작 전'과 '집계 전'을 구분한다.
	 */
	@Override
	public List<RoundRow> findRounds(RoundCriteria criteria) {
		String sql = """
				SELECT
					r.assessment_round_id,
					r.round_no,
					p.sequence_no AS cohort_round_no,
					r.round_name,
					p.project_id,
					p.name AS project_name,
					r.status AS round_status,
					EXISTS (
						SELECT 1
						FROM report rpt
						WHERE rpt.assessment_round_id = r.assessment_round_id
							AND rpt.lifecycle_status = 'ACTIVE'
							AND rpt.published_at IS NOT NULL
					) AS report_published
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE r.cohort_id = ?
					AND r.org_id = ?
					AND r.deleted_at IS NULL
					AND p.project_category = ?%s
					AND p.sequence_no BETWEEN ? AND ?
				ORDER BY p.sequence_no DESC, r.round_no DESC, r.assessment_round_id DESC
				""".formatted(projectFilter(criteria));

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new RoundRow(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getInt("round_no"),
						rs.getInt("cohort_round_no"),
						rs.getString("round_name"),
						rs.getObject("project_id", UUID.class),
						rs.getString("project_name"),
						rs.getString("round_status"),
						rs.getBoolean("report_published")
				),
				appendRoundRange(
						scopeArguments(criteria),
						criteria
				)
		);
	}

	@Override
	public int countRegisteredRounds(RoundCriteria criteria) {
		String sql = """
				SELECT COUNT(*)
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE r.cohort_id = ?
					AND r.org_id = ?
					AND r.deleted_at IS NULL
					AND p.project_category = ?%s
				""".formatted(projectFilter(criteria));

		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, scopeArguments(criteria));
		return count == null ? 0 : count;
	}

	/**
	 * projectId를 지정했을 때만 프로젝트 조건을 붙인다.
	 * 바인딩 파라미터에 NULL UUID를 넘겨 `? IS NULL` 로 분기하면 PostgreSQL이 타입을 추론하지 못해
	 * 명시적 캐스팅이 필요해지므로, 조건 자체를 넣고 빼는 쪽이 단순하다.
	 */
	private String projectFilter(RoundCriteria criteria) {
		return criteria.projectId() == null ? "" : "\n\t\t\t\tAND p.project_id = ?";
	}

	private Object[] scopeArguments(RoundCriteria criteria) {
		if (criteria.projectId() == null) {
			return new Object[]{criteria.cohortId(), criteria.organizationId(), criteria.projectCategory()};
		}
		return new Object[]{
				criteria.cohortId(),
				criteria.organizationId(),
				criteria.projectCategory(),
				criteria.projectId()
		};
	}

	private Object[] appendRoundRange(Object[] scope, RoundCriteria criteria) {
		Object[] merged = Arrays.copyOf(scope, scope.length + 2);
		merged[scope.length] = criteria.fromRoundNo();
		merged[scope.length + 1] = criteria.toRoundNo();
		return merged;
	}

	/**
	 * 회차 × 반 격자를 한 번의 질의로 집계한다.
	 *
	 * 분모(eligible)와 분자(risk)를 같은 attempt_scope에서 뽑아 반 귀속을 한 경로로 통일한다.
	 * interview_candidate.class_id를 그대로 쓰지 않는 이유는 분모 쪽 반 귀속과 어긋나면
	 * 반별 분자가 분모를 넘을 수 있기 때문이다.
	 */
	@Override
	public List<RiskCellRow> aggregateRiskCells(RoundCriteria criteria) {
		String sql = """
				WITH %5$s,
				round_scope AS (
					SELECT
						r.assessment_round_id,
						COALESCE(r.submission_due_at, r.scheduled_at, r.updated_at) AS class_anchor_at,
						crs.analysis_sequence_no = 1 AS is_first_round
					FROM project_assessment_round r
					JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
					JOIN cohort_round_sequence crs ON crs.assessment_round_id = r.assessment_round_id
					WHERE r.cohort_id = ?
						AND r.org_id = ?
						AND r.deleted_at IS NULL
						AND p.project_category = ?%1$s
						AND p.sequence_no BETWEEN ? AND ?
				),
				attempt_scope AS (
					SELECT
						ma.assessment_round_id,
						ma.user_id,
						round_class.class_id,
						ma.terminal_reason_code,
						ma.validity_review_status,
						rs.is_first_round
					FROM measurement_attempt ma
					JOIN round_scope rs ON rs.assessment_round_id = ma.assessment_round_id
					LEFT JOIN LATERAL (
						SELECT cmb.class_id
						FROM cohort_member cm
						JOIN class_membership cmb ON cmb.cohort_member_id = cm.cohort_member_id
						JOIN "class" cl ON cl.class_id = cmb.class_id AND cl.deleted_at IS NULL
						WHERE cm.user_id = ma.user_id
							AND cm.cohort_id = ma.cohort_id
							AND cm.org_id = ma.org_id
							AND cmb.assigned_at <= rs.class_anchor_at
							AND (cmb.unassigned_at IS NULL OR cmb.unassigned_at > rs.class_anchor_at)
						ORDER BY cmb.assigned_at DESC, cmb.class_membership_id DESC
						LIMIT 1
					) round_class ON TRUE
					WHERE ma.attempt_type = 'INITIAL'
						AND ma.cohort_id = ?
						AND ma.org_id = ?
				),
				risk_scope AS (
					SELECT
						ic.assessment_round_id,
						ic.user_id,
						BOOL_OR(icr.evaluation_status = 'MATCHED') AS matched,
						BOOL_OR(icr.evaluation_status = 'NOT_APPLICABLE') AS observed
					FROM interview_candidate ic
					JOIN interview_candidate_reason icr ON icr.candidate_id = ic.candidate_id
					JOIN round_scope rs ON rs.assessment_round_id = ic.assessment_round_id
					WHERE ic.cohort_id = ?
						AND ic.org_id = ?
						AND icr.reason_code IN (%2$s)
						AND icr.evaluation_status IN ('MATCHED', 'NOT_APPLICABLE')
						AND icr.reason_status = 'ACTIVE'
					GROUP BY ic.assessment_round_id, ic.user_id
				)
				SELECT
					a.assessment_round_id,
					a.class_id,
					COUNT(*) FILTER (WHERE %3$s) AS eligible_count,
					COUNT(*) FILTER (WHERE %4$s AND %3$s) AS risk_count,
					COUNT(*) FILTER (WHERE %6$s AND %3$s) AS observed_risk_count,
					COUNT(*) FILTER (WHERE a.terminal_reason_code = 'NOT_ATTENDED') AS not_attended_count,
					COUNT(*) FILTER (WHERE a.terminal_reason_code = 'SESSION_INCOMPLETE') AS session_incomplete_count,
					COUNT(*) FILTER (WHERE a.validity_review_status = 'CONFIRMED_INVALID') AS invalid_attempt_count
				FROM attempt_scope a
				LEFT JOIN risk_scope r
					ON r.assessment_round_id = a.assessment_round_id
					AND r.user_id = a.user_id
				GROUP BY a.assessment_round_id, a.class_id
				""".formatted(
				projectFilter(criteria),
				RISK_REASON_CODES,
				ELIGIBLE_CONDITION,
				RISK_CONDITION,
				COHORT_ROUND_SEQUENCE_CTE.strip(),
				OBSERVED_CONDITION
		);

		// cohort_round_sequence → round_scope → attempt_scope → risk_scope 순서로 다시 바인딩한다.
		List<Object> arguments = new ArrayList<>();
		arguments.add(criteria.cohortId());
		arguments.add(criteria.organizationId());
		arguments.add(criteria.projectCategory());
		arguments.addAll(List.of(appendRoundRange(scopeArguments(criteria), criteria)));
		arguments.add(criteria.cohortId());
		arguments.add(criteria.organizationId());
		arguments.add(criteria.cohortId());
		arguments.add(criteria.organizationId());

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new RiskCellRow(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("class_id", UUID.class),
						rs.getLong("eligible_count"),
						rs.getLong("risk_count"),
						rs.getLong("not_attended_count"),
						rs.getLong("session_incomplete_count"),
						rs.getLong("invalid_attempt_count"),
						rs.getLong("observed_risk_count")
				),
				arguments.toArray()
		);
	}

	/**
	 * 팀 격자. 분모·분자 조건은 반 격자와 같고 귀속 경로만 팀으로 바꾼다.
	 *
	 * 반 귀속은 cohort_member → class_membership을 쓰지만 팀은 프로젝트 참여를 거쳐야 한다.
	 * 회차 시점(class_anchor_at)에 유효했던 팀 배정 한 건만 남겨 팀을 옮긴 교육생이 두 팀에
	 * 중복 계상되지 않게 한다.
	 */
	@Override
	public List<TeamRiskCellRow> aggregateTeamRiskCells(RoundCriteria criteria, UUID classroomId) {
		String sql = """
				WITH %5$s,
				round_scope AS (
					SELECT
						r.assessment_round_id,
						COALESCE(r.submission_due_at, r.scheduled_at, r.updated_at) AS class_anchor_at,
						crs.analysis_sequence_no = 1 AS is_first_round
					FROM project_assessment_round r
					JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
					JOIN cohort_round_sequence crs ON crs.assessment_round_id = r.assessment_round_id
					WHERE r.cohort_id = ?
						AND r.org_id = ?
						AND r.deleted_at IS NULL
						AND p.project_category = ?%1$s
						AND p.sequence_no BETWEEN ? AND ?
				),
				attempt_scope AS (
					SELECT
						ma.assessment_round_id,
						ma.user_id,
						round_team.team_id,
						ma.terminal_reason_code,
						ma.validity_review_status,
						rs.is_first_round
					FROM measurement_attempt ma
					JOIN round_scope rs ON rs.assessment_round_id = ma.assessment_round_id
					JOIN LATERAL (
						SELECT t.team_id
						FROM project_membership pm
						JOIN team_membership tm ON tm.project_membership_id = pm.project_membership_id
						JOIN team t ON t.team_id = tm.team_id AND t.deleted_at IS NULL
						WHERE pm.project_id = ma.project_id
							AND pm.user_id = ma.user_id
							AND pm.class_id = ?
							AND tm.from_at <= rs.class_anchor_at
							AND (tm.to_at IS NULL OR tm.to_at > rs.class_anchor_at)
						ORDER BY tm.from_at DESC, t.team_id DESC
						LIMIT 1
					) round_team ON TRUE
					WHERE ma.attempt_type = 'INITIAL'
						AND ma.cohort_id = ?
						AND ma.org_id = ?
				),
				risk_scope AS (
					SELECT
						ic.assessment_round_id,
						ic.user_id,
						BOOL_OR(icr.evaluation_status = 'MATCHED') AS matched,
						BOOL_OR(icr.evaluation_status = 'NOT_APPLICABLE') AS observed
					FROM interview_candidate ic
					JOIN interview_candidate_reason icr ON icr.candidate_id = ic.candidate_id
					JOIN round_scope rs ON rs.assessment_round_id = ic.assessment_round_id
					WHERE ic.cohort_id = ?
						AND ic.org_id = ?
						AND icr.reason_code IN (%2$s)
						AND icr.evaluation_status IN ('MATCHED', 'NOT_APPLICABLE')
						AND icr.reason_status = 'ACTIVE'
					GROUP BY ic.assessment_round_id, ic.user_id
				)
				SELECT
					a.assessment_round_id,
					a.team_id,
					COUNT(*) FILTER (WHERE %3$s) AS eligible_count,
					COUNT(*) FILTER (WHERE %4$s AND %3$s) AS risk_count,
					COUNT(*) FILTER (WHERE %6$s AND %3$s) AS observed_risk_count,
					COUNT(*) FILTER (WHERE a.terminal_reason_code = 'NOT_ATTENDED') AS not_attended_count,
					COUNT(*) FILTER (WHERE a.terminal_reason_code = 'SESSION_INCOMPLETE') AS session_incomplete_count,
					COUNT(*) FILTER (WHERE a.validity_review_status = 'CONFIRMED_INVALID') AS invalid_attempt_count
				FROM attempt_scope a
				LEFT JOIN risk_scope r
					ON r.assessment_round_id = a.assessment_round_id
					AND r.user_id = a.user_id
				GROUP BY a.assessment_round_id, a.team_id
				""".formatted(
				projectFilter(criteria),
				RISK_REASON_CODES,
				ELIGIBLE_CONDITION,
				RISK_CONDITION,
				COHORT_ROUND_SEQUENCE_CTE.strip(),
				OBSERVED_CONDITION
		);

		List<Object> arguments = new ArrayList<>();
		arguments.add(criteria.cohortId());
		arguments.add(criteria.organizationId());
		arguments.add(criteria.projectCategory());
		arguments.addAll(List.of(appendRoundRange(scopeArguments(criteria), criteria)));
		arguments.add(classroomId);
		arguments.add(criteria.cohortId());
		arguments.add(criteria.organizationId());
		arguments.add(criteria.cohortId());
		arguments.add(criteria.organizationId());

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new TeamRiskCellRow(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("team_id", UUID.class),
						rs.getLong("eligible_count"),
						rs.getLong("risk_count"),
						rs.getLong("not_attended_count"),
						rs.getLong("session_incomplete_count"),
						rs.getLong("invalid_attempt_count"),
						rs.getLong("observed_risk_count")
				),
				arguments.toArray()
		);
	}

	@Override
	public List<TeamRosterRow> findTeamRosters(UUID projectId, UUID classroomId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				SELECT
					t.team_id,
					t.team_number,
					t.name AS team_name,
					t.class_id,
					c.name AS class_name,
					(
						SELECT COUNT(*)
						FROM team_membership tm
						WHERE tm.team_id = t.team_id AND tm.to_at IS NULL
					) AS member_count
				FROM team t
				JOIN "class" c ON c.class_id = t.class_id AND c.deleted_at IS NULL
				WHERE t.project_id = ?
					AND t.class_id = ?
					AND t.org_id = ?
					AND t.deleted_at IS NULL
				ORDER BY t.team_number, t.team_id
				""",
				(rs, rowNum) -> new TeamRosterRow(
						rs.getObject("team_id", UUID.class),
						rs.getString("team_number"),
						rs.getString("team_name"),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getLong("member_count")
				),
				projectId,
				classroomId,
				organizationId
		);
	}

	/**
	 * 담당 매니저는 LATERAL로 먼저 배열로 접어 넣는다.
	 * 한 반에 매니저가 여럿이면 그냥 조인할 때 반 행이 매니저 수만큼 불어나 인원 집계가 부풀려진다.
	 */
	@Override
	public List<ClassRosterRow> findClassRosters(UUID cohortId, UUID organizationId) {
		// 반을 옮긴 교육생이 두 반에 중복 계상되지 않도록 최근 배정 한 건만 남긴다.
		return jdbcTemplate.query(
				"""
				WITH member_class AS (
					SELECT DISTINCT ON (cm.cohort_member_id)
						cm.cohort_member_id,
						cm.left_at,
						cmb.class_id
					FROM cohort_member cm
					JOIN app_user u ON u.user_id = cm.user_id
					JOIN class_membership cmb ON cmb.cohort_member_id = cm.cohort_member_id
					WHERE cm.cohort_id = ?
						AND cm.org_id = ?
						AND u.role_code = 'TRAINEE'
					ORDER BY cm.cohort_member_id, cmb.assigned_at DESC, cmb.class_membership_id DESC
				)
				SELECT
					cl.class_id,
					cl.name AS class_name,
					COUNT(mc.cohort_member_id) FILTER (WHERE mc.left_at IS NULL) AS trainee_count,
					COUNT(mc.cohort_member_id) FILTER (WHERE mc.left_at IS NOT NULL) AS withdrawn_count,
					COALESCE(mgr.manager_names, ARRAY[]::text[]) AS manager_names
				FROM "class" cl
				LEFT JOIN member_class mc ON mc.class_id = cl.class_id
				LEFT JOIN LATERAL (
					SELECT ARRAY_AGG(u.name ORDER BY u.name) AS manager_names
					FROM manager_assignment m
					JOIN app_user u ON u.user_id = m.manager_user_id
					WHERE m.class_id = cl.class_id
						AND m.status = 'ACTIVE'
						AND m.unassigned_at IS NULL
				) mgr ON TRUE
				WHERE cl.cohort_id = ?
					AND cl.org_id = ?
					AND cl.deleted_at IS NULL
				GROUP BY cl.class_id, cl.name, mgr.manager_names
				ORDER BY cl.name, cl.class_id
				""",
				(rs, rowNum) -> new ClassRosterRow(
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getLong("trainee_count"),
						rs.getLong("withdrawn_count"),
						textArray(rs, "manager_names")
				),
				cohortId,
				organizationId,
				cohortId,
				organizationId
		);
	}

	private List<String> textArray(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		if (array == null) {
			return List.of();
		}
		return Arrays.stream((String[]) array.getArray()).filter(Objects::nonNull).toList();
	}

	@Override
	public RosterCount findCohortRoster(UUID cohortId, UUID organizationId) {
		RosterCount roster = jdbcTemplate.query(
				"""
				SELECT
					COUNT(*) FILTER (WHERE cm.left_at IS NULL) AS trainee_count,
					COUNT(*) FILTER (WHERE cm.left_at IS NOT NULL) AS withdrawn_count
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				WHERE cm.cohort_id = ?
					AND cm.org_id = ?
					AND u.role_code = 'TRAINEE'
				""",
				(rs, rowNum) -> new RosterCount(rs.getLong("trainee_count"), rs.getLong("withdrawn_count")),
				cohortId,
				organizationId
		).stream().findFirst().orElse(null);
		return roster == null ? new RosterCount(0, 0) : roster;
	}
}
