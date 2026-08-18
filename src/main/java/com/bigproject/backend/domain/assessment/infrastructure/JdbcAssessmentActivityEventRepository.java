package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.AssessmentActivityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAssessmentActivityEventRepository implements AssessmentActivityEventRepository {

	private final JdbcTemplate jdbcTemplate;

	/** {@code JdbcAssessmentValidityRepository.lockAttempt}와 같은 스코프 조인이다. 읽기 전용이라 락은 없다. */
	@Override
	public Optional<UUID> findScopedSessionId(UUID attemptId, UUID managerId) {
		List<UUID> rows = jdbcTemplate.query("""
				SELECT s.session_id
				FROM measurement_attempt ma
				JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id
				JOIN manager_assignment mgr ON mgr.class_id = pm.class_id
				JOIN assessment_session s ON s.attempt_id = ma.attempt_id
				WHERE ma.attempt_id = ? AND mgr.manager_user_id = ?
				  AND mgr.status = 'ACTIVE' AND mgr.unassigned_at IS NULL
				ORDER BY pm.joined_at DESC
				LIMIT 1
				""", (rs, rowNum) -> rs.getObject("session_id", UUID.class), attemptId, managerId);
		return rows.stream().findFirst();
	}

	@Override
	public List<ProblemRow> findProblems(UUID sessionId) {
		return jdbcTemplate.query("""
				SELECT DISTINCT ap.problem_id, ap.problem_no
				FROM assessment_problem ap
				JOIN problem_stage ps ON ps.problem_id = ap.problem_id
				WHERE ps.session_id = ?
				ORDER BY ap.problem_no
				""",
				(rs, rowNum) -> new ProblemRow(rs.getObject("problem_id", UUID.class), rs.getInt("problem_no")),
				sessionId);
	}

	/** {@code l.session_id}로 바로 거른다 — {@code problem_stage} 조인 없이 세션 단위로 훑을 수 있도록 둔 중복 컬럼이다. */
	@Override
	public List<EventRow> findEvents(UUID sessionId) {
		return jdbcTemplate.query("""
				SELECT ps.problem_id, l.event_type, l.started_at, l.duration_ms
				FROM problem_stage_activity_log l
				JOIN problem_stage ps ON ps.problem_stage_id = l.problem_stage_id
				WHERE l.session_id = ?
				ORDER BY ps.problem_id, l.event_type, l.started_at
				""",
				(rs, rowNum) -> new EventRow(rs.getObject("problem_id", UUID.class), rs.getString("event_type"),
						rs.getTimestamp("started_at").toInstant(), rs.getInt("duration_ms")),
				sessionId);
	}
}
