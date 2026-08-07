package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ClassProgressQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcClassProgressQueryRepository implements ClassProgressQueryRepository {

	private final JdbcTemplate jdbcTemplate;

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
					p.org_id
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
						rs.getObject("org_id", UUID.class)
				),
				projectId,
				roundNo
		).stream().findFirst();
	}

	/**
	 * 담당 매니저는 LATERAL로 먼저 배열로 접어 넣는다.
	 * 한 반에 매니저가 여럿이면 그냥 조인할 때 반 행이 매니저 수만큼 불어나 인원 집계가 부풀려진다.
	 */
	@Override
	public List<ClassProgressRow> findClassProgress(UUID assessmentRoundId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				SELECT
					a.class_id,
					c.name AS class_name,
					COUNT(*) AS target_trainee_count,
					COUNT(*) FILTER (WHERE a.source_submission_id IS NOT NULL) AS submitted_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'SUCCEEDED') AS analysis_succeeded_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'FAILED') AS analysis_failed_count,
					COUNT(*) FILTER (WHERE a.analysis_status = 'PARTIAL') AS analysis_partial_count,
					COUNT(*) FILTER (WHERE a.analysis_status IN ('QUEUED', 'RUNNING')) AS analysis_in_progress_count,
					COUNT(*) FILTER (WHERE a.completion_status = 'COMPLETED') AS assessed_count,
					COUNT(*) FILTER (WHERE a.primary_terminal_reason_code = 'NOT_ATTENDED') AS not_attended_count,
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
				GROUP BY a.class_id, c.name, mgr.manager_names
				ORDER BY c.name, a.class_id
				""",
				(rs, rowNum) -> new ClassProgressRow(
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getLong("target_trainee_count"),
						rs.getLong("submitted_count"),
						rs.getLong("analysis_succeeded_count"),
						rs.getLong("analysis_failed_count"),
						rs.getLong("analysis_partial_count"),
						rs.getLong("analysis_in_progress_count"),
						rs.getLong("assessed_count"),
						rs.getLong("not_attended_count"),
						rs.getLong("session_incomplete_count"),
						rs.getLong("invalid_attempt_count"),
						textArray(rs, "manager_names")
				),
				assessmentRoundId,
				organizationId
		);
	}

	@Override
	public List<ConceptMatchRow> findConceptMatches(UUID assessmentRoundId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				WITH analysed_member AS (
					SELECT a.team_id, a.user_id
					FROM assessment_round_attendance a
					WHERE a.assessment_round_id = ?
						AND a.org_id = ?
						AND a.analysis_status = 'SUCCEEDED'
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
				assessmentRoundId,
				organizationId,
				assessmentRoundId
		);
	}

	private List<String> textArray(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		if (array == null) {
			return List.of();
		}
		return Arrays.stream((String[]) array.getArray()).filter(java.util.Objects::nonNull).toList();
	}
}
