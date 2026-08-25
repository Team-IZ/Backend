package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.ManagerAnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcManagerAnalyticsRepository implements ManagerAnalyticsRepository {
	// 매니저 담당 반으로 조회를 가두는 조인이다. 모든 격자 쿼리가 이 조건을 지난다.
	private static final String MANAGER_SCOPE = """
			JOIN manager_assignment mgr ON mgr.class_id = %s
			  AND mgr.manager_user_id = ? AND mgr.status = 'ACTIVE' AND mgr.unassigned_at IS NULL
			""";

	/**
	 * 격자 뷰의 {@code problem_id}로 개념을 되찾는 조인이다.
	 *
	 * <h2>왜 {@code problem_no}가 가로축이 될 수 없나</h2>
	 *
	 * <p>{@code uq_assessment_problem_code_analysis_id_problem_no}가 말하듯 문제 순번은
	 * <b>팀 분석 하나 안에서만</b> 유일하다. 팀마다 {@code code_analysis}가 따로 돌고 순번은
	 * 그때 다시 1부터 매겨지므로, 한 회차 안에서도 어떤 팀의 1번이 다른 팀의 2번일 수 있다.
	 * 실제로 그런 팀이 있었고, 결과가 두 가지였다 —
	 *
	 * <ul>
	 *   <li>열 머리는 {@code (problem_no, teaches_id)} 조합이라 3개가 아니라 5개가 나왔고,
	 *       셀은 {@code problem_no}로만 묶여 3개라 오른쪽 두 칸이 빈 채로 남았다</li>
	 *   <li>더 나쁜 쪽 — 순번이 다른 그 팀의 결과가 <b>다른 개념의 열에 섞여</b> 평균에 들어갔다</li>
	 * </ul>
	 *
	 * <p>그래서 가로축을 개념({@code teaches_id})으로 옮긴다. 네 격자 뷰는 {@code teaches_id}를
	 * 내보내지 않으므로 {@code problem_id}로 되짚는다.
	 */
	private static final String CONCEPT_JOIN = """
			JOIN assessment_problem ap ON ap.problem_id = h.problem_id
			LEFT JOIN project_verification_concept pvc
			  ON pvc.project_concept_id = ap.project_verification_concept_id
			""";

	/**
	 * 집계 셀의 계산식이다.
	 *
	 * <p>{@code manager_class_heatmap_view}·{@code manager_team_heatmap_view}를 쓰지 않는다 —
	 * 두 뷰는 이미 {@code problem_no}로 묶은 뒤 {@code problem_id}를 버려서 개념 단위로 다시
	 * 묶을 수가 없다. 두 뷰는 개인 뷰를 한 단계 더 묶은 것에 지나지 않고(같은 CTE·같은 식·같은
	 * {@code INITIAL} 조건), 그 한 단계를 여기서 개념 축으로 대신 낸다. {@code findSummary}가
	 * 이미 같은 이유로 개인 뷰에서 반 평균을 낸다.
	 */
	private static final String AGGREGATE_CELL = """
			  (AVG(h.highest_reached_level) FILTER (
			    WHERE h.problem_result_status = 'VALID'))::numeric(10,4) AS value,
			  (CASE WHEN COUNT(*) = 0 THEN 'EMPTY'
			    WHEN COUNT(*) FILTER (WHERE h.problem_result_status = 'VALID') = 0 THEN 'NO_VALID_RESULT'
			    ELSE 'COMPLETE' END)::varchar(20) AS status,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'VALID')::integer AS valid_count,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'NOT_ATTENDED')::integer AS not_attended_count,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'INVALID')::integer AS invalid_count,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'INTERRUPTED')::integer AS interrupted_count,
			  NULL::integer AS initial_highest_reached_level,
			  NULL::integer AS comparison_highest_reached_level, NULL::integer AS comparison_delta,
			  MAX(h.as_of_at) AS as_of_at
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<HeatmapCell> findHeatmap(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			String level, String attemptView, UUID classroomId, UUID teamId) {
		if ("REVIEW".equals(attemptView)) {
			String sql = """
					SELECT h.user_id AS row_id, u.name AS row_name,
					  COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
					  h.comparison_highest_reached_level::numeric AS value,
					  h.comparison_result_status AS status,
					  NULL::integer AS valid_count, NULL::integer AS not_attended_count,
					  NULL::integer AS invalid_count, NULL::integer AS interrupted_count,
					  h.initial_highest_reached_level, h.comparison_highest_reached_level,
					  h.comparison_delta, h.as_of_at
					FROM manager_retried_trainee_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + CONCEPT_JOIN + """
					JOIN app_user u ON u.user_id = h.user_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.comparison_attempt_type = 'REVIEW'
					  AND h.class_id = ? AND h.team_id = ?
					  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
					ORDER BY u.name
					""";
			return jdbcTemplate.query(sql, this::mapHeatmap,
					managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
		}
		return switch (level) {
			case "CLASS" -> jdbcTemplate.query("""
					SELECT h.class_id AS row_id, c.name AS row_name,
					  COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
					""" + AGGREGATE_CELL + """
					FROM manager_trainee_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + CONCEPT_JOIN + """
					JOIN class c ON c.class_id = h.class_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
					GROUP BY h.class_id, c.name, COALESCE(ap.teaches_id, pvc.teaches_id)
					ORDER BY c.name
					""", this::mapHeatmap, managerId, cohortId, projectId, assessmentRoundId);
			case "TEAM" -> jdbcTemplate.query("""
					SELECT h.team_id AS row_id, t.name AS row_name,
					  COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
					""" + AGGREGATE_CELL + """
					FROM manager_trainee_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + CONCEPT_JOIN + """
					JOIN team t ON t.team_id = h.team_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.class_id = ?
					  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
					GROUP BY h.team_id, t.name, COALESCE(ap.teaches_id, pvc.teaches_id)
					ORDER BY t.name
					""", this::mapHeatmap, managerId, cohortId, projectId, assessmentRoundId, classroomId);
			case "TRAINEE" -> jdbcTemplate.query("""
					SELECT h.user_id AS row_id, u.name AS row_name,
					  COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
					  h.highest_reached_level::numeric AS value, h.problem_result_status AS status,
					  NULL::integer AS valid_count, NULL::integer AS not_attended_count,
					  NULL::integer AS invalid_count, NULL::integer AS interrupted_count,
					  NULL::integer AS initial_highest_reached_level,
					  NULL::integer AS comparison_highest_reached_level, NULL::integer AS comparison_delta,
					  h.as_of_at
					FROM manager_trainee_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + CONCEPT_JOIN + """
					JOIN app_user u ON u.user_id = h.user_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.class_id = ? AND h.team_id = ?
					  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
					ORDER BY u.name
					""", this::mapHeatmap, managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
			default -> List.of();
		};
	}

	// 반별 평균을 다시 평균 내면 인원이 다른 반이 같은 무게가 되므로 개인 단위에서 한 번에 낸다.
	@Override
	public List<HeatmapCell> findSummary(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId) {
		String sql = """
				SELECT NULL::uuid AS row_id, NULL::text AS row_name,
				  COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
				""" + AGGREGATE_CELL + """
				FROM manager_trainee_heatmap_view h
				""" + MANAGER_SCOPE.formatted("h.class_id") + CONCEPT_JOIN + """
				WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
				  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
				  AND (?::uuid IS NULL OR h.class_id = ?::uuid)
				  AND (?::uuid IS NULL OR h.team_id = ?::uuid)
				GROUP BY COALESCE(ap.teaches_id, pvc.teaches_id)
				""";
		return jdbcTemplate.query(sql, this::mapHeatmap, managerId, cohortId, projectId,
				assessmentRoundId, classroomId, classroomId, teamId, teamId);
	}

	// 명부 인원이라 응시하지 않은 사람도 센다. 셀의 valid_count와 다른 모집단이다.
	@Override
	public int countMembers(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId) {
		String sql = """
				SELECT COUNT(DISTINCT pm.user_id)::integer
				FROM project_membership pm
				JOIN project p ON p.project_id = pm.project_id AND p.cohort_id = ?
				JOIN project_assessment_round r ON r.project_id = p.project_id
				  AND r.assessment_round_id = ?
				""" + MANAGER_SCOPE.formatted("pm.class_id") + """
				LEFT JOIN LATERAL (
				  SELECT x.team_id FROM team_membership x
				  WHERE x.project_membership_id = pm.project_membership_id
				  ORDER BY x.from_at DESC LIMIT 1
				) tm ON TRUE
				WHERE pm.project_id = ? AND pm.status = 'ACTIVE'
				  AND (?::uuid IS NULL OR pm.class_id = ?::uuid)
				  AND (?::uuid IS NULL OR tm.team_id = ?::uuid)
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, cohortId, assessmentRoundId,
				managerId, projectId, classroomId, classroomId, teamId, teamId);
		return count == null ? 0 : count;
	}

	/*
	 * 34차 R2·R3 — 결과가 하나도 없는 인원.
	 *
	 * 개인 뷰 하나로 세 계층을 다 센다. 팀 뷰에도 problem_no IS NULL 묶음이 있지만, 팀 뷰는 이미
	 * 집계된 값이라 「몇 명인지」를 되찾을 수 없다. 사람 수를 세는 것이 이 조회의 목적이라 개인
	 * grain에서 센다.
	 *
	 * 이름 조인이 LEFT인 이유는 인터페이스 설명 참고 — 팀 배정이 회차 창에 안 걸린 인원도 합계에는
	 * 들어가야 한다.
	 */
	private static final String UNRESOLVED_SQL = """
			SELECT %1$s AS row_id, %2$s AS row_name,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'NOT_ATTENDED')::integer AS not_attended_count,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'INVALID')::integer AS invalid_count,
			  COUNT(*) FILTER (WHERE h.problem_result_status = 'INTERRUPTED')::integer AS interrupted_count,
			  COUNT(*) FILTER (WHERE h.problem_result_status NOT IN
			    ('NOT_ATTENDED','INVALID','INTERRUPTED','VALID'))::integer AS pending_count
			FROM manager_trainee_heatmap_view h
			""" + MANAGER_SCOPE.formatted("h.class_id") + """
			%3$s
			WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
			  AND h.problem_no IS NULL
			  AND (?::uuid IS NULL OR h.class_id = ?::uuid)
			  AND (?::uuid IS NULL OR h.team_id = ?::uuid)
			GROUP BY %1$s, %2$s
			ORDER BY %2$s
			""";

	@Override
	public List<UnresolvedGroup> findUnresolved(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			String level, UUID classroomId, UUID teamId) {
		String sql = switch (level) {
			case "CLASS" -> UNRESOLVED_SQL.formatted(
					"h.class_id", "c.name", "LEFT JOIN class c ON c.class_id = h.class_id");
			case "TEAM" -> UNRESOLVED_SQL.formatted(
					"h.team_id", "t.name", "LEFT JOIN team t ON t.team_id = h.team_id");
			case "TRAINEE" -> UNRESOLVED_SQL.formatted(
					"h.user_id", "u.name", "LEFT JOIN app_user u ON u.user_id = h.user_id");
			default -> null;
		};
		if (sql == null) {
			return List.of();
		}
		return jdbcTemplate.query(sql, (rs, n) -> new UnresolvedGroup(
				rs.getObject("row_id", UUID.class), rs.getString("row_name"),
				rs.getInt("not_attended_count"), rs.getInt("invalid_count"),
				rs.getInt("interrupted_count"), rs.getInt("pending_count")),
				managerId, cohortId, projectId, assessmentRoundId,
				classroomId, classroomId, teamId, teamId);
	}

	/**
	 * 가로축이다. 회차의 개념을 한 번씩만 낸다.
	 *
	 * <p>순서는 문제 순번의 <b>평균</b>으로 정한다. 대다수 팀이 1번에 둔 개념이 첫 열에 오고,
	 * 순번을 다르게 매긴 소수 팀이 순서를 흔들지 못한다. {@code MIN}을 쓰면 그 소수 팀 하나가
	 * 열 순서를 바꿔 버린다.
	 */
	@Override
	public List<ConceptAxis> findConcepts(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId) {
		String sql = """
				SELECT COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
				  MIN(t.canonical_name) AS concept_name
				FROM measurement_attempt ma
				JOIN assessment_session s ON s.attempt_id = ma.attempt_id
				JOIN problem_stage ps ON ps.session_id = s.session_id
				JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
				LEFT JOIN project_verification_concept pvc
				  ON pvc.project_concept_id = ap.project_verification_concept_id
				LEFT JOIN teaches t ON t.teaches_id = COALESCE(ap.teaches_id, pvc.teaches_id)
				JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id
				""" + MANAGER_SCOPE.formatted("pm.class_id") + """
				WHERE ma.cohort_id = ? AND ma.project_id = ? AND ma.assessment_round_id = ?
				  AND ma.attempt_type = 'INITIAL'
				  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
				GROUP BY COALESCE(ap.teaches_id, pvc.teaches_id)
				ORDER BY AVG(ap.problem_no), MIN(t.canonical_name)
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new ConceptAxis(
				rs.getObject("teaches_id", UUID.class), rs.getString("concept_name")),
				managerId, cohortId, projectId, assessmentRoundId);
	}

	// 유효 응시자 중 절반을 넘는 인원이 2단 이하이면 집단 미달이다. 미응시·무효·중단은 분모에서 뺀다.
	@Override
	public List<GroupShortfall> findGroupShortfall(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId) {
		String sql = """
				SELECT h.class_id, COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
				  COUNT(*) FILTER (WHERE h.problem_result_status = 'VALID')::integer AS valid_count,
				  COUNT(*) FILTER (WHERE h.problem_result_status = 'VALID'
				    AND h.highest_reached_level <= 2)::integer AS low_count
				FROM manager_trainee_heatmap_view h
				""" + MANAGER_SCOPE.formatted("h.class_id") + CONCEPT_JOIN + """
				WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
				  AND COALESCE(ap.teaches_id, pvc.teaches_id) IS NOT NULL
				GROUP BY h.class_id, COALESCE(ap.teaches_id, pvc.teaches_id)
				""";
		return jdbcTemplate.query(sql, (rs, n) -> {
			int validCount = rs.getInt("valid_count");
			return new GroupShortfall(
					rs.getObject("class_id", UUID.class), rs.getObject("teaches_id", UUID.class),
					validCount == 0 ? null : rs.getInt("low_count") * 2 > validCount);
		}, managerId, cohortId, projectId, assessmentRoundId);
	}

	@Override
	public List<ClassParticipant> findParticipatingClassrooms(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId) {
		String sql = """
				SELECT pm.class_id, c.name AS class_name,
				  COUNT(DISTINCT pm.user_id)::integer AS member_count
				FROM project_membership pm
				JOIN class c ON c.class_id = pm.class_id
				JOIN project p ON p.project_id = pm.project_id AND p.cohort_id = ?
				JOIN project_assessment_round r ON r.project_id = p.project_id
				  AND r.assessment_round_id = ?
				""" + MANAGER_SCOPE.formatted("pm.class_id") + """
				WHERE pm.project_id = ? AND pm.status = 'ACTIVE'
				GROUP BY pm.class_id, c.name
				ORDER BY c.name
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new ClassParticipant(
				rs.getObject("class_id", UUID.class), rs.getString("class_name"), rs.getInt("member_count")),
				cohortId, assessmentRoundId, managerId, projectId);
	}

	@Override
	public List<TeamParticipant> findParticipatingTeams(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId, UUID classroomId) {
		String sql = """
				SELECT tm.team_id, t.name AS team_name,
				  COUNT(DISTINCT pm.user_id)::integer AS member_count
				FROM project_membership pm
				JOIN project p ON p.project_id = pm.project_id AND p.cohort_id = ?
				JOIN project_assessment_round r ON r.project_id = p.project_id
				  AND r.assessment_round_id = ?
				""" + MANAGER_SCOPE.formatted("pm.class_id") + """
				JOIN LATERAL (
				  SELECT x.team_id FROM team_membership x
				  WHERE x.project_membership_id = pm.project_membership_id
				  ORDER BY x.from_at DESC LIMIT 1
				) tm ON TRUE
				JOIN team t ON t.team_id = tm.team_id
				WHERE pm.project_id = ? AND pm.status = 'ACTIVE' AND pm.class_id = ?
				GROUP BY tm.team_id, t.name
				ORDER BY t.name
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new TeamParticipant(
				rs.getObject("team_id", UUID.class), rs.getString("team_name"), rs.getInt("member_count")),
				cohortId, assessmentRoundId, managerId, projectId, classroomId);
	}

	@Override
	public List<RiskSignal> findRiskSignals(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID classroomId,
			UUID traineeId, String reasonCode) {
		String sql = """
				WITH candidate_signal AS (
				SELECT r.candidate_reason_id AS signal_id, r.reason_code, c.assessment_round_id,
				  c.class_id, c.team_id, c.user_id, u.name AS trainee_name,
				  r.reason_summary, r.reason_status, r.policy_version, r.detected_at
				FROM interview_candidate_reason r
				JOIN interview_candidate c ON c.candidate_id = r.candidate_id
				JOIN app_user u ON u.user_id = c.user_id
				JOIN manager_assignment ma ON ma.class_id = c.class_id
				WHERE ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				  AND c.cohort_id = ?
				  AND (?::uuid IS NULL OR c.assessment_round_id = ?::uuid)
				  AND (?::uuid IS NULL OR c.class_id = ?::uuid)
				  AND (?::uuid IS NULL OR c.user_id = ?::uuid)
				  AND (?::text IS NULL OR r.reason_code = ?::text)
				), derived_signal AS (
				SELECT (v.source_trace::jsonb ->> 'attemptId')::uuid AS signal_id,
				  v.risk_type_code AS reason_code, v.assessment_round_id, pm.class_id,
				  tm.team_id, v.user_id, u.name AS trainee_name, v.source_trace AS reason_summary,
				  'ACTIVE' AS reason_status, v.policy_version, v.calculated_at AS detected_at
				FROM manager_trainee_risk_view v
				JOIN measurement_attempt a ON a.attempt_id = (v.source_trace::jsonb ->> 'attemptId')::uuid
				JOIN project p ON p.project_id = a.project_id AND p.cohort_id = ?
				JOIN project_membership pm ON pm.project_id = a.project_id AND pm.user_id = v.user_id
				JOIN manager_assignment ma ON ma.class_id = pm.class_id
				  AND ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				LEFT JOIN LATERAL (SELECT x.team_id FROM team_membership x
				  WHERE x.project_membership_id = pm.project_membership_id
				  ORDER BY x.from_at DESC LIMIT 1) tm ON TRUE
				JOIN app_user u ON u.user_id = v.user_id
				WHERE v.is_applicable = TRUE
				  AND (?::uuid IS NULL OR v.assessment_round_id = ?::uuid)
				  AND (?::uuid IS NULL OR pm.class_id = ?::uuid)
				  AND (?::uuid IS NULL OR v.user_id = ?::uuid)
				  AND (?::text IS NULL OR v.risk_type_code = ?::text)
				  AND NOT EXISTS (SELECT 1 FROM candidate_signal c
				    WHERE c.assessment_round_id = v.assessment_round_id AND c.user_id = v.user_id
				      AND c.reason_code = v.risk_type_code)
				)
				SELECT * FROM candidate_signal UNION ALL SELECT * FROM derived_signal
				ORDER BY detected_at DESC, signal_id
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new RiskSignal(
				rs.getObject("signal_id", UUID.class), rs.getString("reason_code"),
				rs.getObject("assessment_round_id", UUID.class), rs.getObject("class_id", UUID.class),
				rs.getObject("team_id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("trainee_name"), rs.getString("reason_summary"), rs.getString("reason_status"),
				rs.getInt("policy_version"), time(rs.getTimestamp("detected_at"))),
				managerId, cohortId, assessmentRoundId, assessmentRoundId, classroomId, classroomId,
				traineeId, traineeId, reasonCode, reasonCode, cohortId, managerId,
				assessmentRoundId, assessmentRoundId, classroomId, classroomId,
				traineeId, traineeId, reasonCode, reasonCode);
	}

	@Override
	public ConceptScope findConceptScope(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID classroomId, UUID teachesId) {
		// 분모는 히트맵 집단 미달과 같은 유효 응시자다. 반 명부 인원을 쓰면 같은 개념에
		// 두 API가 다른 답을 낸다.
		String sql = """
				WITH round_scope AS (
				  SELECT r.assessment_round_id, r.project_id
				  FROM project_assessment_round r JOIN project p ON p.project_id = r.project_id
				  JOIN class c ON c.cohort_id = p.cohort_id
				  JOIN manager_assignment mgr ON mgr.class_id = c.class_id
				  WHERE r.assessment_round_id = ? AND p.cohort_id = ? AND c.class_id = ?
				    AND mgr.manager_user_id = ? AND mgr.status = 'ACTIVE' AND mgr.unassigned_at IS NULL
				), user_level AS (
				  SELECT ma.user_id,
				    MAX(CASE WHEN ps.status = 'PASSED' THEN CASE ps.axis_code
				      WHEN 'L4' THEN 4 WHEN 'L3' THEN 3 WHEN 'L2' THEN 2 WHEN 'L1' THEN 1 ELSE 0 END ELSE 0 END) AS level
				  FROM round_scope r
				  JOIN measurement_attempt ma ON ma.assessment_round_id = r.assessment_round_id
				  JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id AND pm.class_id = ?
				  JOIN assessment_session s ON s.attempt_id = ma.attempt_id
				  JOIN problem_stage ps ON ps.session_id = s.session_id
				  JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
				  LEFT JOIN project_verification_concept pvc ON pvc.project_concept_id = ap.project_verification_concept_id
				  WHERE ma.attempt_type = 'INITIAL' AND ma.status = 'COMPLETED'
				    AND ma.validity_review_status <> 'CONFIRMED_INVALID'
				    AND COALESCE(ap.teaches_id, pvc.teaches_id) = ?
				  GROUP BY ma.user_id
				), result AS (
				  SELECT COUNT(*) FILTER (WHERE level <= 2)::bigint AS low_count,
				    COUNT(*)::bigint AS valid_count FROM user_level
				)
				SELECT t.teaches_id, t.canonical_name, result.low_count, result.valid_count,
				  CASE WHEN result.valid_count = 0 THEN NULL
				    ELSE result.low_count::numeric / result.valid_count::numeric END AS low_rate,
				  CASE WHEN result.low_count * 2 > result.valid_count
				    THEN 'CLASS_WIDE' ELSE 'INDIVIDUAL' END AS scope,
				  1 AS policy_version, CURRENT_TIMESTAMP AS calculated_at
				FROM teaches t CROSS JOIN result
				WHERE t.teaches_id = ? AND EXISTS (SELECT 1 FROM round_scope)
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new ConceptScope(
				rs.getObject("teaches_id", UUID.class), rs.getString("canonical_name"),
				rs.getLong("low_count"), rs.getLong("valid_count"), rs.getBigDecimal("low_rate"),
				rs.getString("scope"), rs.getInt("policy_version"), time(rs.getTimestamp("calculated_at"))),
				assessmentRoundId, cohortId, classroomId, managerId, classroomId,
				teachesId, teachesId).stream().findFirst().orElse(null);
	}

	private HeatmapCell mapHeatmap(ResultSet rs, int n) throws SQLException {
		return new HeatmapCell(
				rs.getObject("row_id", UUID.class), rs.getString("row_name"),
				rs.getObject("teaches_id", UUID.class),
				rs.getBigDecimal("value"), rs.getString("status"), integer(rs, "valid_count"),
				integer(rs, "not_attended_count"), integer(rs, "invalid_count"), integer(rs, "interrupted_count"),
				integer(rs, "initial_highest_reached_level"), integer(rs, "comparison_highest_reached_level"),
				integer(rs, "comparison_delta"), time(rs.getTimestamp("as_of_at")));
	}

	private Integer integer(ResultSet rs, String name) throws SQLException {
		int value = rs.getInt(name); return rs.wasNull() ? null : value;
	}

	private OffsetDateTime time(Timestamp value) {
		return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
	}
}
