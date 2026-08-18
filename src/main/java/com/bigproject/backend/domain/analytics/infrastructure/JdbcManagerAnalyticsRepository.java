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

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<HeatmapCell> findHeatmap(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			String level, String attemptView, UUID classroomId, UUID teamId) {
		if ("REVIEW".equals(attemptView)) {
			String sql = """
					SELECT h.user_id AS row_id, u.name AS row_name, h.problem_no,
					  h.comparison_highest_reached_level::numeric AS value,
					  h.comparison_result_status AS status,
					  NULL::integer AS valid_count, NULL::integer AS not_attended_count,
					  NULL::integer AS invalid_count, NULL::integer AS interrupted_count,
					  h.initial_highest_reached_level, h.comparison_highest_reached_level,
					  h.comparison_delta, h.as_of_at
					FROM manager_retried_trainee_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + """
					JOIN app_user u ON u.user_id = h.user_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.comparison_attempt_type = 'REVIEW'
					  AND h.class_id = ? AND h.team_id = ? AND h.problem_no IS NOT NULL
					ORDER BY u.name, h.problem_no
					""";
			return jdbcTemplate.query(sql, this::mapHeatmap,
					managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
		}
		return switch (level) {
			case "CLASS" -> jdbcTemplate.query("""
					SELECT h.class_id AS row_id, c.name AS row_name, h.problem_no,
					  h.average_highest_reached_level AS value, h.aggregation_status AS status,
					  h.valid_result_count AS valid_count, h.not_attended_count,
					  h.invalid_attempt_count AS invalid_count, h.interrupted_count,
					  NULL::integer AS initial_highest_reached_level,
					  NULL::integer AS comparison_highest_reached_level, NULL::integer AS comparison_delta,
					  h.as_of_at
					FROM manager_class_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + """
					JOIN class c ON c.class_id = h.class_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.problem_no IS NOT NULL
					ORDER BY c.name, h.problem_no
					""", this::mapHeatmap, managerId, cohortId, projectId, assessmentRoundId);
			case "TEAM" -> jdbcTemplate.query("""
					SELECT h.team_id AS row_id, t.name AS row_name, h.problem_no,
					  h.average_highest_reached_level AS value, h.aggregation_status AS status,
					  h.valid_result_count AS valid_count, h.not_attended_count,
					  h.invalid_attempt_count AS invalid_count, h.interrupted_count,
					  NULL::integer AS initial_highest_reached_level,
					  NULL::integer AS comparison_highest_reached_level, NULL::integer AS comparison_delta,
					  h.as_of_at
					FROM manager_team_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + """
					JOIN team t ON t.team_id = h.team_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.class_id = ? AND h.problem_no IS NOT NULL
					ORDER BY t.name, h.problem_no
					""", this::mapHeatmap, managerId, cohortId, projectId, assessmentRoundId, classroomId);
			case "TRAINEE" -> jdbcTemplate.query("""
					SELECT h.user_id AS row_id, u.name AS row_name, h.problem_no,
					  h.highest_reached_level::numeric AS value, h.problem_result_status AS status,
					  NULL::integer AS valid_count, NULL::integer AS not_attended_count,
					  NULL::integer AS invalid_count, NULL::integer AS interrupted_count,
					  NULL::integer AS initial_highest_reached_level,
					  NULL::integer AS comparison_highest_reached_level, NULL::integer AS comparison_delta,
					  h.as_of_at
					FROM manager_trainee_heatmap_view h
					""" + MANAGER_SCOPE.formatted("h.class_id") + """
					JOIN app_user u ON u.user_id = h.user_id
					WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
					  AND h.class_id = ? AND h.team_id = ? AND h.problem_no IS NOT NULL
					ORDER BY u.name, h.problem_no
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
				SELECT NULL::uuid AS row_id, NULL::text AS row_name, h.problem_no,
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
				FROM manager_trainee_heatmap_view h
				""" + MANAGER_SCOPE.formatted("h.class_id") + """
				WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
				  AND h.problem_no IS NOT NULL
				  AND (?::uuid IS NULL OR h.class_id = ?::uuid)
				  AND (?::uuid IS NULL OR h.team_id = ?::uuid)
				GROUP BY h.problem_no
				ORDER BY h.problem_no
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

	@Override
	public List<ConceptAxis> findConcepts(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId) {
		String sql = """
				SELECT DISTINCT ap.problem_no,
				  COALESCE(ap.teaches_id, pvc.teaches_id) AS teaches_id,
				  t.canonical_name AS concept_name
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
				ORDER BY ap.problem_no
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new ConceptAxis(
				rs.getInt("problem_no"), rs.getObject("teaches_id", UUID.class), rs.getString("concept_name")),
				managerId, cohortId, projectId, assessmentRoundId);
	}

	// 유효 응시자 중 절반을 넘는 인원이 2단 이하이면 집단 미달이다. 미응시·무효·중단은 분모에서 뺀다.
	@Override
	public List<GroupShortfall> findGroupShortfall(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId) {
		String sql = """
				SELECT h.class_id, h.problem_no,
				  COUNT(*) FILTER (WHERE h.problem_result_status = 'VALID')::integer AS valid_count,
				  COUNT(*) FILTER (WHERE h.problem_result_status = 'VALID'
				    AND h.highest_reached_level <= 2)::integer AS low_count
				FROM manager_trainee_heatmap_view h
				""" + MANAGER_SCOPE.formatted("h.class_id") + """
				WHERE h.cohort_id = ? AND h.project_id = ? AND h.assessment_round_id = ?
				  AND h.problem_no IS NOT NULL
				GROUP BY h.class_id, h.problem_no
				""";
		return jdbcTemplate.query(sql, (rs, n) -> {
			int validCount = rs.getInt("valid_count");
			return new GroupShortfall(
					rs.getObject("class_id", UUID.class), rs.getInt("problem_no"),
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
				rs.getObject("row_id", UUID.class), rs.getString("row_name"), rs.getInt("problem_no"),
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
