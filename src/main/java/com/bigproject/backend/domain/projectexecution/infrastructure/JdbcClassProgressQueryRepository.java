package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ClassProgressQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcClassProgressQueryRepository implements ClassProgressQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 담당 반 제한(30차 R3). {@code scopedManagerId}가 없으면 빈 문자열이라 SQL이 그대로 남는다.
	 *
	 * <p><b>조인이 아니라 {@code EXISTS}로 건다.</b> {@code manager_assignment}는 배정·해제 이력이
	 * 쌓이는 표라 같은 (매니저, 반)에 행이 여럿일 수 있고, 조인하면 그 수만큼 출석 행이 복제돼
	 * <b>인원 집계가 부풀려진다</b> — 제출 현황({@code JdbcSubmissionStatusQueryRepository})이 같은
	 * 이유로 같은 모양을 쓴다. 두 탭이 같은 조건을 써야 같은 모집단을 말한다.
	 *
	 * <p>파라미터는 매니저 ID 하나이고, 호출부의 인자 순서가 이 조각의 위치와 맞아야 한다.
	 */
	private static String managedClassFilter(UUID scopedManagerId, String classColumn) {
		if (scopedManagerId == null) {
			return "";
		}
		return """
					AND EXISTS (
						SELECT 1
						FROM manager_assignment ma_scope
						WHERE ma_scope.class_id = %s
							AND ma_scope.manager_user_id = ?
							AND ma_scope.status = 'ACTIVE'
							AND ma_scope.unassigned_at IS NULL
					)
				""".formatted(classColumn);
	}

	/**
	 * 반이 아니라 <b>팀</b>으로 좁힌다. 개념 매칭은 팀 단위 판정이라 반 컬럼이 없다.
	 *
	 * <p>{@code team} 조인을 FROM에 더하지 않고 {@code EXISTS} 안에 가두는 이유는, 오퍼레이터가
	 * 부를 때의 질의를 <b>한 글자도 바꾸지 않기 위해서</b>다. 조인을 밖에 두면 삭제된 팀이
	 * 결과에서 빠지는 등 좁히는 것과 무관한 변화가 따라붙는다.
	 */
	private static String managedTeamFilter(UUID scopedManagerId, String teamColumn) {
		if (scopedManagerId == null) {
			return "";
		}
		return """
					AND EXISTS (
						SELECT 1
						FROM team t_scope
						JOIN manager_assignment ma_scope ON ma_scope.class_id = t_scope.class_id
						WHERE t_scope.team_id = %s
							AND ma_scope.manager_user_id = ?
							AND ma_scope.status = 'ACTIVE'
							AND ma_scope.unassigned_at IS NULL
					)
				""".formatted(teamColumn);
	}

	/**
	 * 조건이 SQL에 없으면 인자도 없어야 한다. 매니저 ID는 <b>조각이 놓인 자리</b>에 들어가므로
	 * 질의마다 앞뒤 인자를 나눠 넘긴다 — 조각이 WHERE 끝에 오지 않는 질의가 있다.
	 */
	private static Object[] argsAround(List<Object> before, UUID scopedManagerId, Object... after) {
		List<Object> all = new ArrayList<>(before);
		if (scopedManagerId != null) {
			all.add(scopedManagerId);
		}
		all.addAll(Arrays.asList(after));
		return all.toArray();
	}

	@Override
	public Optional<RoundScope> findRound(UUID projectId, int roundNo) {
		return jdbcTemplate.query(
				"""
				SELECT
					r.assessment_round_id,
					p.project_id,
					p.name AS project_name,
					r.round_no,
					r.round_name,
					p.org_id,
					p.cohort_id,
					r.submission_due_at,
					r.report_publish_mode,
					EXISTS (
						SELECT 1
						FROM report rpt
						WHERE rpt.assessment_round_id = r.assessment_round_id
							AND rpt.lifecycle_status = 'ACTIVE'
							AND rpt.published_at IS NOT NULL
					) AS report_published,
					(
						SELECT COUNT(*)
						FROM project_assessment_round r2
						WHERE r2.project_id = r.project_id
							AND r2.deleted_at IS NULL
					) AS total_round_count
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE r.project_id = ?
					AND r.round_no = ?
					AND r.deleted_at IS NULL
				""",
				(rs, rowNum) -> new RoundScope(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("project_id", UUID.class),
						rs.getString("project_name"),
						rs.getInt("round_no"),
						rs.getString("round_name"),
						rs.getObject("org_id", UUID.class),
						rs.getObject("cohort_id", UUID.class),
						instant(rs, "submission_due_at"),
						rs.getString("report_publish_mode"),
						rs.getBoolean("report_published"),
						rs.getInt("total_round_count")
				),
				projectId,
				roundNo
		).stream().findFirst();
	}

	@Override
	public boolean hasAnyRound(UUID projectId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
					SELECT 1 FROM project_assessment_round
					WHERE project_id = ? AND deleted_at IS NULL
				)
				""", Boolean.class, projectId));
	}

	/**
	 * 담당 매니저는 LATERAL로 먼저 배열로 접어 넣는다.
	 * 한 반에 매니저가 여럿이면 그냥 조인할 때 반 행이 매니저 수만큼 불어나 인원 집계가 부풀려진다.
	 */
	@Override
	public List<ClassProgressRow> findClassProgress(
			UUID assessmentRoundId, UUID organizationId, UUID scopedManagerId) {
		return jdbcTemplate.query(
				"""
				SELECT
					a.class_id,
					c.name AS class_name,
					COUNT(*) AS target_trainee_count,
					COUNT(*) FILTER (WHERE a.source_submission_id IS NOT NULL) AS submitted_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'SUCCEEDED') AS analysis_succeeded_count,
					-- 응시율의 분모. PARTIAL은 일부 개념만 문항이 생성된 경우이고 그 문항으로 응시할 수
					-- 있으므로 대상에 넣는다 — 빼면 응시율이 부풀려진다.
					COUNT(*) FILTER (WHERE a.analysis_status IN ('SUCCEEDED', 'PARTIAL'))
						AS assessment_target_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'FAILED') AS analysis_failed_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'PARTIAL') AS analysis_partial_count,
					COUNT(*) FILTER (WHERE a.analysis_status IN ('QUEUED', 'RUNNING')) AS analysis_in_progress_count,
					COUNT(*) FILTER (WHERE a.completion_status = 'COMPLETED') AS assessed_count,
					-- 검증 세션을 하지 못한 인원. 미제출·분석 실패도 결국 세션을 못 한 것이라 함께 센다.
					-- 결과 탭(EvaluationService.resultStatus)의 NOT_ATTENDED와 같은 기준이다.
					COUNT(*) FILTER (
						WHERE a.primary_terminal_reason_code
							IN ('NOT_ATTENDED', 'NOT_SUBMITTED', 'ANALYSIS_FAILED')
					) AS not_attended_count,
					-- 그중 볼 수 있었는데 안 본 인원. 나머지는 볼 수 없었던 경우라 조치가 다르다.
					COUNT(*) FILTER (WHERE a.primary_terminal_reason_code = 'NOT_ATTENDED') AS no_show_count,
					COUNT(*) FILTER (WHERE a.primary_terminal_reason_code = 'SESSION_INCOMPLETE')
						AS session_incomplete_count,
					COUNT(*) FILTER (WHERE ma.validity_review_status = 'CONFIRMED_INVALID')
						AS invalid_attempt_count,
					COALESCE(mgr.manager_names, ARRAY[]::text[]) AS manager_names
				FROM assessment_round_attendance a
				JOIN "class" c ON c.class_id = a.class_id AND c.deleted_at IS NULL
				LEFT JOIN measurement_attempt ma ON ma.attempt_id = a.primary_attempt_id
				LEFT JOIN LATERAL (
					SELECT ARRAY_AGG(u.name ORDER BY u.name) AS manager_names
					FROM manager_assignment m
					JOIN app_user u ON u.user_id = m.manager_user_id
					WHERE m.class_id = a.class_id
						AND m.status = 'ACTIVE'
						AND m.unassigned_at IS NULL
				) mgr ON TRUE
				WHERE a.assessment_round_id = ?
					AND a.org_id = ?
				""" + managedClassFilter(scopedManagerId, "a.class_id") + """
				GROUP BY a.class_id, c.name, mgr.manager_names
				ORDER BY c.name, a.class_id
				""",
				(rs, rowNum) -> new ClassProgressRow(
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getLong("target_trainee_count"),
						rs.getLong("submitted_count"),
						rs.getLong("analysis_succeeded_count"),
						rs.getLong("assessment_target_count"),
						rs.getLong("analysis_failed_count"),
						rs.getLong("analysis_partial_count"),
						rs.getLong("analysis_in_progress_count"),
						rs.getLong("assessed_count"),
						rs.getLong("not_attended_count"),
						rs.getLong("no_show_count"),
						rs.getLong("session_incomplete_count"),
						rs.getLong("invalid_attempt_count"),
						textArray(rs, "manager_names")
				),
				argsAround(List.of(assessmentRoundId, organizationId), scopedManagerId)
		);
	}

	/**
	 * 반별 GROUP BY 없이 회차 전체를 바로 집계한다. classes[] 합산 값과 항상 같아야 하지만
	 * 화면 카드가 classes[]의 필터·정렬에 얽매이지 않도록 별도 질의로 낸다.
	 */
	@Override
	public RoundSummaryRow findRoundSummary(
			UUID assessmentRoundId, UUID organizationId, UUID scopedManagerId) {
		return jdbcTemplate.queryForObject(
				"""
				SELECT
					COUNT(*) AS target_trainee_count,
					COUNT(*) FILTER (WHERE a.source_submission_id IS NOT NULL) AS submitted_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'SUCCEEDED') AS analysis_succeeded_count,
					COUNT(*) FILTER (WHERE a.analysis_status IN ('SUCCEEDED', 'PARTIAL'))
						AS assessment_target_count,
					COUNT(*) FILTER (WHERE a.completion_status = 'COMPLETED') AS assessed_count
				FROM assessment_round_attendance a
				WHERE a.assessment_round_id = ?
					AND a.org_id = ?
				""" + managedClassFilter(scopedManagerId, "a.class_id"),
				(rs, rowNum) -> {
					long submittedCount = rs.getLong("submitted_count");
					return new RoundSummaryRow(
							rs.getLong("target_trainee_count"),
							submittedCount,
							submittedCount,
							rs.getLong("analysis_succeeded_count"),
							rs.getLong("assessment_target_count"),
							rs.getLong("assessed_count")
					);
				},
				argsAround(List.of(assessmentRoundId, organizationId), scopedManagerId)
		);
	}

	@Override
	public List<ConceptMatchRow> findConceptMatches(
			UUID assessmentRoundId, UUID organizationId, UUID scopedManagerId) {
		return jdbcTemplate.query(
				"""
				WITH analysed_member AS (
					SELECT a.team_id, a.user_id
					FROM assessment_round_attendance a
					WHERE a.assessment_round_id = ?
						AND a.org_id = ?
						AND a.analysis_status = 'SUCCEEDED'
				""" + managedClassFilter(scopedManagerId, "a.class_id") + """
				),
				team_concept AS (
					SELECT
						ca.team_id,
						pvc.teaches_id,
						BOOL_OR(ap.generation_status = 'GENERATED') AS matched
					FROM assessment_problem ap
					JOIN code_analysis ca ON ca.analysis_id = ap.code_analysis_id AND ca.status = 'ACTIVE'
					JOIN project_verification_concept pvc
						ON pvc.project_concept_id = ap.project_verification_concept_id
					WHERE ap.problem_scope = 'TEAM_SHARED_PROBLEM'
						AND ca.assessment_round_id = ?
				""" + managedTeamFilter(scopedManagerId, "ca.team_id") + """
					GROUP BY ca.team_id, pvc.teaches_id
				)
				SELECT
					tc.teaches_id,
					t.canonical_name AS concept_name,
					COUNT(am.user_id) AS analysed_trainee_count,
					COUNT(am.user_id) FILTER (WHERE tc.matched) AS matched_trainee_count,
					COUNT(DISTINCT tc.team_id) FILTER (WHERE NOT tc.matched) AS unmatched_team_count
				FROM team_concept tc
				JOIN teaches t ON t.teaches_id = tc.teaches_id
				LEFT JOIN analysed_member am ON am.team_id = tc.team_id
				GROUP BY tc.teaches_id, t.canonical_name
				ORDER BY t.canonical_name
				""",
				(rs, rowNum) -> new ConceptMatchRow(
						rs.getObject("teaches_id", UUID.class),
						rs.getString("concept_name"),
						rs.getLong("analysed_trainee_count"),
						rs.getLong("matched_trainee_count"),
						rs.getLong("unmatched_team_count")
				),
				// 조각이 두 CTE에 하나씩 들어가므로 매니저 ID도 두 번, 각 조각의 자리에 실린다.
				conceptMatchArgs(assessmentRoundId, organizationId, scopedManagerId)
		);
	}

	private static Object[] conceptMatchArgs(
			UUID assessmentRoundId, UUID organizationId, UUID scopedManagerId) {
		List<Object> args = new ArrayList<>();
		args.add(assessmentRoundId);   // analysed_member
		args.add(organizationId);      // analysed_member
		if (scopedManagerId != null) {
			args.add(scopedManagerId); // analysed_member의 담당 반 조각
		}
		args.add(assessmentRoundId);   // team_concept
		if (scopedManagerId != null) {
			args.add(scopedManagerId); // team_concept의 담당 팀 조각
		}
		return args.toArray();
	}

	/**
	 * 팀·회차별 최신 제출(is_current DESC, submitted_at DESC)과 그 제출에 매인 최신 분석 시도
	 * (execution_no DESC, started_at DESC)만 본다 — assessment_round_attendance 뷰가 analysis_status를
	 * 고르는 것과 같은 기준이라 classes[].analysisFailedCount와 팀 목록이 어긋나지 않는다.
	 */
	@Override
	public List<FailedTeamRow> findFailedTeams(
			UUID assessmentRoundId, UUID organizationId, UUID scopedManagerId) {
		return jdbcTemplate.query(
				"""
				SELECT
					t.class_id,
					t.team_id,
					t.name AS team_name,
					u.user_id AS representative_user_id,
					u.name AS representative_name,
					aj.failure_reason
				FROM team t
				JOIN LATERAL (
					SELECT x.* FROM submission x
					WHERE x.team_id = t.team_id AND x.assessment_round_id = ?
					ORDER BY x.is_current DESC, x.submitted_at DESC LIMIT 1
				) sub ON TRUE
				JOIN LATERAL (
					SELECT x.* FROM analysis_job x
					WHERE x.assessment_round_id = ? AND x.team_id = t.team_id
						AND x.submission_id = sub.submission_id
					ORDER BY x.execution_no DESC, x.started_at DESC NULLS LAST, x.job_id DESC LIMIT 1
				) aj ON TRUE
				JOIN app_user u ON u.user_id = sub.submitted_by
				WHERE t.org_id = ?
					AND t.deleted_at IS NULL
					AND aj.status = 'FAILED'
				""" + managedClassFilter(scopedManagerId, "t.class_id") + """
				ORDER BY t.name
				""",
				(rs, rowNum) -> new FailedTeamRow(
						rs.getObject("class_id", UUID.class),
						rs.getObject("team_id", UUID.class),
						rs.getString("team_name"),
						rs.getObject("representative_user_id", UUID.class),
						rs.getString("representative_name"),
						rs.getString("failure_reason")
				),
				argsAround(List.of(assessmentRoundId, assessmentRoundId, organizationId), scopedManagerId)
		);
	}

	private List<String> textArray(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		if (array == null) {
			return List.of();
		}
		return Arrays.stream((String[]) array.getArray()).filter(java.util.Objects::nonNull).toList();
	}

	/** TIMESTAMPTZ → Instant. null 컬럼을 0 epoch로 만들지 않으려면 getTimestamp를 거쳐야 한다. */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
