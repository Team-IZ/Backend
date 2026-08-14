package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewSourceRepository implements InterviewSourceRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 여섯 근거 컬럼 중 하나만 채우는 INSERT.
	 *
	 * <p>컬럼 이름을 문자열로 끼워 넣지만 <b>호출부가 상수만 넘긴다</b>(아래 public 메서드들) —
	 * 외부 입력이 닿지 않으므로 주입 경로가 없다. 여섯 벌의 거의 같은 SQL을 두는 것보다
	 * 한 곳에서 CHECK 제약을 지키는 편이 어긋날 자리가 적다.
	 */
	private UUID insert(UUID interviewId, String sourceType, String column, UUID referenceId, UUID actorUserId) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO interview_source
				    (interview_id, source_type, %s, source_captured_at, created_by)
				VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
				RETURNING interview_source_id
				""".formatted(column),
				UUID.class,
				interviewId, sourceType, referenceId, actorUserId);
	}

	@Override
	public UUID createFromCandidateReason(UUID interviewId, UUID candidateReasonId, UUID actorUserId) {
		return insert(interviewId, "CANDIDATE_REASON", "candidate_reason_id", candidateReasonId, actorUserId);
	}

	@Override
	public UUID createFromAttempt(UUID interviewId, UUID attemptId, UUID actorUserId) {
		return insert(interviewId, "ATTEMPT", "attempt_id", attemptId, actorUserId);
	}

	@Override
	public UUID createFromSession(UUID interviewId, UUID sessionId, UUID actorUserId) {
		return insert(interviewId, "SESSION", "session_id", sessionId, actorUserId);
	}

	@Override
	public UUID createFromProblem(UUID interviewId, UUID problemId, UUID actorUserId) {
		return insert(interviewId, "PROBLEM", "problem_id", problemId, actorUserId);
	}

	@Override
	public UUID createFromProblemStage(UUID interviewId, UUID problemStageId, UUID actorUserId) {
		return insert(interviewId, "PROBLEM_STAGE", "problem_stage_id", problemStageId, actorUserId);
	}

	@Override
	public UUID createFromObservationNote(UUID interviewId, UUID noteId, UUID actorUserId) {
		return insert(interviewId, "OBSERVATION_NOTE", "observation_note_id", noteId, actorUserId);
	}
}
