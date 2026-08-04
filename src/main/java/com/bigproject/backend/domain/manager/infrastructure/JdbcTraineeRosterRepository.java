package com.bigproject.backend.domain.manager.infrastructure;

import com.bigproject.backend.domain.manager.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MG-05 교육생 명부의 복합 조회 구현체. SQL은 PostgreSQL 전용으로 작성한다.
 *
 * <p>PostgreSQL 고유 문법을 사용하는 지점:
 * <ul>
 *   <li>{@code = ANY(?)} + {@code uuid[]} 바인딩 — 반 목록·사용자 목록을 자리표시자 문자열로 조립하지 않는다.</li>
 *   <li>{@code COUNT(*) FILTER (WHERE ...)} — 계정 상태별 헤더 집계와 회차별 우수 판정에 사용한다.</li>
 *   <li>{@code ROW_NUMBER() OVER (...)} — 화면의 "미프 N차"에 해당하는 분석 순서 재번호화에 사용한다.</li>
 *   <li>{@code ILIKE ... ESCAPE} — 대소문자 무시 이름 검색과 와일드카드 이스케이프에 사용한다.</li>
 *   <li>{@code SUBSTRING(axis_code, 2)::INTEGER} — L1~L4 축 코드를 도달 단계 숫자로 변환한다.</li>
 * </ul>
 *
 * <p>이름 정렬(DEFAULT)은 DB에서 바로 LIMIT/OFFSET으로 페이지네이션한다. 우수 누적·2단 이하 정렬은 정렬 키 자체가
 * 회차·개념·문제 단계를 가로지르는 집계값이라 반 범위로 필터링된 전체 대상을 먼저 가져온 뒤 서비스 계층에서 정렬·페이지
 * 슬라이스를 수행한다(담당 반 규모는 학급 단위라 전체 materialize 비용이 낮다).
 */
@Repository
@RequiredArgsConstructor
public class JdbcTraineeRosterRepository implements TraineeRosterRepository {
	private final JdbcTemplate jdbcTemplate;

	@Override
	public Page<RosterMemberRow> findRosterPage(Criteria criteria, UUID assessmentRoundId, int page, int size) {
		if (criteria.classIds().isEmpty()) {
			return new Page<>(List.of(), 0);
		}
		QueryParts base = buildBaseQuery(criteria, assessmentRoundId);

		// 요청 페이지가 전체 페이지를 넘어서면 행은 0건이지만 총 건수는 유지되어야 하므로 COUNT는 별도 쿼리로 조회한다.
		Long total = jdbcTemplate.query(
				statement("SELECT COUNT(*) " + base.sql(), base.params()),
				rs -> rs.next() ? rs.getLong(1) : 0L
		);

		String sql = ROSTER_SELECT + base.sql() + """
				ORDER BY LOWER(COALESCE(NULLIF(u.name, ''), u.email)), u.user_id
				LIMIT ? OFFSET ?
				""";
		List<Object> pageParams = new ArrayList<>(base.params());
		pageParams.add(size);
		pageParams.add((long) page * size);
		List<RosterMemberRow> content = jdbcTemplate.query(statement(sql, pageParams), ROSTER_MEMBER_MAPPER);
		return new Page<>(content, total == null ? 0 : total);
	}

	@Override
	public List<RosterMemberRow> findAllRosterMembers(Criteria criteria, UUID assessmentRoundId) {
		if (criteria.classIds().isEmpty()) {
			return List.of();
		}
		QueryParts base = buildBaseQuery(criteria, assessmentRoundId);
		String sql = ROSTER_SELECT + base.sql() + " ORDER BY LOWER(COALESCE(NULLIF(u.name, ''), u.email)), u.user_id";
		return jdbcTemplate.query(statement(sql, base.params()), ROSTER_MEMBER_MAPPER);
	}

	private static final String ROSTER_SELECT = """
			SELECT
				cm.cohort_member_id,
				u.user_id,
				u.name,
				u.email,
				u.status AS app_user_status,
				u.deleted_at,
				c.class_id,
				c.name AS class_name,
				matt.attempt_id,
				matt.status AS attempt_status,
				matt.validity_review_status
			""";

	private static final RowMapper<RosterMemberRow> ROSTER_MEMBER_MAPPER = (rs, rowNum) -> new RosterMemberRow(
			rs.getObject("cohort_member_id", UUID.class),
			rs.getObject("user_id", UUID.class),
			rs.getString("name"),
			rs.getString("email"),
			rs.getString("app_user_status"),
			rs.getObject("deleted_at") != null,
			rs.getObject("class_id", UUID.class),
			rs.getString("class_name"),
			rs.getObject("attempt_id", UUID.class),
			rs.getString("attempt_status"),
			rs.getString("validity_review_status")
	);

	private QueryParts buildBaseQuery(Criteria criteria, UUID assessmentRoundId) {
		// 교육생 명단을 기준으로 결과 원천을 LEFT JOIN하여 미응시·분석 중·결과 전 교육생도 목록에서 제거하지 않는다.
		StringBuilder sql = new StringBuilder("""
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				JOIN "role" r ON r.role_id = u.role_id
				JOIN class_membership cml ON cml.cohort_member_id = cm.cohort_member_id AND cml.unassigned_at IS NULL
				JOIN "class" c ON c.class_id = cml.class_id AND c.deleted_at IS NULL
				LEFT JOIN measurement_attempt matt
					ON matt.user_id = u.user_id
					AND matt.assessment_round_id = ?
					AND matt.attempt_type = 'INITIAL'
				WHERE cm.cohort_id = ?
					AND cm.org_id = ?
					AND cm.status = 'ACTIVE'
					AND r.code = 'TRAINEE'
					AND cml.class_id = ANY(?)
				""");
		List<Object> params = new ArrayList<>();
		params.add(assessmentRoundId);
		params.add(criteria.cohortId());
		params.add(criteria.organizationId());
		params.add(criteria.classIds());

		appendAccountStatusFilter(sql, params, criteria.accountStatus());
		appendSearchFilter(sql, params, criteria.normalizedSearchQuery());
		return new QueryParts(sql.toString(), params);
	}

	private void appendAccountStatusFilter(StringBuilder sql, List<Object> params, AccountStatus status) {
		if (status == null) {
			return;
		}
		if (status == AccountStatus.INACTIVE) {
			sql.append(" AND (u.status = 'INACTIVE' OR u.deleted_at IS NOT NULL)");
			return;
		}
		sql.append(" AND u.deleted_at IS NULL AND u.status = ?");
		params.add(status == AccountStatus.INVITED ? "PENDING" : status.name());
	}

	private void appendSearchFilter(StringBuilder sql, List<Object> params, String normalizedSearchQuery) {
		if (normalizedSearchQuery == null) {
			return;
		}
		// 검색 대상은 교육생 이름이다. ILIKE가 대소문자를 무시하므로 별도 LOWER()를 걸지 않는다.
		sql.append(" AND u.name ILIKE ? ESCAPE '\\'");
		params.add("%" + escapeLikeWildcards(normalizedSearchQuery) + "%");
	}

	// 사용자가 입력한 %·_·\가 와일드카드로 해석되지 않도록 이스케이프한다.
	private String escapeLikeWildcards(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	@Override
	public AccountStatusCounts countAccountStatuses(UUID cohortId, UUID organizationId, List<UUID> classIds) {
		if (classIds.isEmpty()) {
			return new AccountStatusCounts(0, 0, 0, 0);
		}
		// 헤더 집계는 계정 필터 적용 전 담당 범위 전체에서 계산하며 total = active + invitation_pending + inactive를 만족한다.
		String sql = """
				SELECT
					COUNT(*) AS total,
					COUNT(*) FILTER (WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE') AS active,
					COUNT(*) FILTER (WHERE u.deleted_at IS NULL AND u.status = 'PENDING') AS invitation_pending,
					COUNT(*) FILTER (
						WHERE u.deleted_at IS NOT NULL OR u.status IN ('INACTIVE', 'LOCKED')
					) AS inactive
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				JOIN "role" r ON r.role_id = u.role_id
				JOIN class_membership cml ON cml.cohort_member_id = cm.cohort_member_id AND cml.unassigned_at IS NULL
				WHERE cm.cohort_id = ?
					AND cm.org_id = ?
					AND cm.status = 'ACTIVE'
					AND r.code = 'TRAINEE'
					AND cml.class_id = ANY(?)
				""";
		List<Object> params = List.of(cohortId, organizationId, classIds);
		AccountStatusCounts counts = jdbcTemplate.query(
				statement(sql, params),
				rs -> rs.next()
						? new AccountStatusCounts(
								rs.getLong("total"),
								rs.getLong("active"),
								rs.getLong("invitation_pending"),
								rs.getLong("inactive"))
						: new AccountStatusCounts(0, 0, 0, 0)
		);
		return counts == null ? new AccountStatusCounts(0, 0, 0, 0) : counts;
	}

	@Override
	public List<ConceptResultRow> findConceptResults(List<UUID> userIds, UUID conceptSetId, UUID assessmentRoundId) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		// 개념별 도달 단계는 통과(PASSED)한 단계 중 가장 높은 축이며, 미출제(NOT_GENERATED)는 0단으로 치환하지 않고 NULL로 남긴다.
		String sql = """
				SELECT
					matt.user_id,
					pvc.project_concept_id AS concept_id,
					pvc.sequence_no,
					t.canonical_name AS concept_name,
					ap.generation_status,
					MAX(CAST(SUBSTRING(ps.axis_code, 2) AS INTEGER))
						FILTER (WHERE ps.status = 'PASSED') AS highest_reached_level
				FROM project_verification_concept pvc
				JOIN teaches t ON t.teaches_id = pvc.teaches_id
				JOIN measurement_attempt matt
					ON matt.assessment_round_id = ?
					AND matt.attempt_type = 'INITIAL'
					AND matt.user_id = ANY(?)
				LEFT JOIN assessment_problem ap
					ON ap.project_verification_concept_id = pvc.project_concept_id
					AND ap.code_analysis_id = matt.code_analysis_id
					AND ap.problem_scope = 'TEAM_SHARED_PROBLEM'
				LEFT JOIN assessment_session ases ON ases.attempt_id = matt.attempt_id
				LEFT JOIN problem_stage ps ON ps.session_id = ases.session_id AND ps.problem_id = ap.problem_id
				WHERE pvc.concept_set_id = ?
				GROUP BY matt.user_id, pvc.project_concept_id, pvc.sequence_no, t.canonical_name, ap.generation_status
				ORDER BY matt.user_id, pvc.sequence_no
				""";
		List<Object> params = List.of(assessmentRoundId, userIds, conceptSetId);
		return jdbcTemplate.query(statement(sql, params), (rs, rowNum) -> new ConceptResultRow(
				rs.getObject("user_id", UUID.class),
				rs.getObject("concept_id", UUID.class),
				rs.getInt("sequence_no"),
				rs.getString("concept_name"),
				rs.getString("generation_status"),
				rs.getObject("highest_reached_level", Integer.class)
		));
	}

	@Override
	public List<ExcellentOccurrenceRow> findExcellentOccurrenceCounts(
			List<UUID> userIds, UUID cohortId, int maxAnalysisSequenceNo
	) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		String sql = """
				WITH mini_project_seq AS (
					SELECT
						p.project_id,
						ROW_NUMBER() OVER (ORDER BY p.sequence_no, p.project_id) AS analysis_sequence_no
					FROM project p
					WHERE p.cohort_id = ?
						AND p.project_category = 'MINI_PROJECT'
						AND p.deleted_at IS NULL
				),
				eligible_round AS (
					SELECT par.assessment_round_id, par.concept_set_id
					FROM project_assessment_round par
					JOIN mini_project_seq mps ON mps.project_id = par.project_id
					WHERE par.deleted_at IS NULL
						AND mps.analysis_sequence_no <= ?
				),
				round_concept_level AS (
					SELECT
						matt.user_id,
						er.assessment_round_id,
						pvc.project_concept_id,
						ap.generation_status,
						MAX(CAST(SUBSTRING(ps.axis_code, 2) AS INTEGER))
							FILTER (WHERE ps.status = 'PASSED') AS reached_level
					FROM eligible_round er
					JOIN project_verification_concept pvc ON pvc.concept_set_id = er.concept_set_id
					JOIN measurement_attempt matt
						ON matt.assessment_round_id = er.assessment_round_id
						AND matt.attempt_type = 'INITIAL'
						AND matt.user_id = ANY(?)
					LEFT JOIN assessment_problem ap
						ON ap.project_verification_concept_id = pvc.project_concept_id
						AND ap.code_analysis_id = matt.code_analysis_id
						AND ap.problem_scope = 'TEAM_SHARED_PROBLEM'
					LEFT JOIN assessment_session ases ON ases.attempt_id = matt.attempt_id
					LEFT JOIN problem_stage ps ON ps.session_id = ases.session_id AND ps.problem_id = ap.problem_id
					GROUP BY matt.user_id, er.assessment_round_id, pvc.project_concept_id, ap.generation_status
				),
				round_excellence AS (
					SELECT
						user_id,
						assessment_round_id,
						COUNT(*) FILTER (
							WHERE generation_status = 'GENERATED' AND reached_level = 4
						) = COUNT(*) AS all_concepts_at_max
					FROM round_concept_level
					GROUP BY user_id, assessment_round_id
				)
				SELECT user_id, COUNT(*) FILTER (WHERE all_concepts_at_max) AS occurrence_count
				FROM round_excellence
				GROUP BY user_id
				""";
		List<Object> params = List.of(cohortId, maxAnalysisSequenceNo, userIds);
		return jdbcTemplate.query(statement(sql, params), (rs, rowNum) -> new ExcellentOccurrenceRow(
				rs.getObject("user_id", UUID.class),
				rs.getInt("occurrence_count")
		));
	}

	/**
	 * 위치 파라미터를 순서대로 바인딩한다. {@code List<UUID>} 값은 PostgreSQL {@code uuid[]}로 바인딩해
	 * {@code = ANY(?)}와 함께 쓰므로 자리표시자 문자열을 목록 크기만큼 조립할 필요가 없다.
	 */
	private PreparedStatementCreator statement(String sql, List<Object> params) {
		return connection -> {
			PreparedStatement statement = connection.prepareStatement(sql);
			for (int index = 0; index < params.size(); index++) {
				Object value = params.get(index);
				if (value instanceof List<?> values) {
					statement.setArray(index + 1, connection.createArrayOf("uuid", values.toArray()));
				} else {
					statement.setObject(index + 1, value);
				}
			}
			return statement;
		};
	}

	private record QueryParts(String sql, List<Object> params) {
	}
}
