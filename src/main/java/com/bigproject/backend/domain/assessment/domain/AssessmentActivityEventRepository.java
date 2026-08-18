package com.bigproject.backend.domain.assessment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 매니저용 이벤트 로그 조회(MG-XX). {@code measurement_attempt}(attemptId)에서 세션을 거쳐
 * {@code problem_stage_activity_log}까지 읽는다.
 *
 * <p>{@code measurement_attempt} : {@code assessment_session}은 1:1이고, {@code assessment_session}
 * : {@code problem_stage}는 1:N이다. {@code problem_stage}는 (세션, 문제, 축) 조합당 1행이라 문제
 * 하나가 최대 4행(L1~L4)을 가지므로, "문제(질문)별" 집계는 {@code problem_stage_id}가 아니라
 * {@code problem_stage.problem_id}로 묶는다.
 */
public interface AssessmentActivityEventRepository {

	/** attemptId가 이 매니저의 담당 범위에 있고 세션이 만들어져 있으면 그 세션 ID를 준다. */
	Optional<UUID> findScopedSessionId(UUID attemptId, UUID managerId);

	/** 이 세션에 stage가 만들어진 문제 목록(문제 번호 순). 이벤트가 없는 문제도 포함한다. */
	List<ProblemRow> findProblems(UUID sessionId);

	/** 이 세션에서 벌어진 이벤트 원본 행(문제별·이벤트타입별·발생순). */
	List<EventRow> findEvents(UUID sessionId);

	record ProblemRow(UUID problemId, int problemNo) {
	}

	record EventRow(UUID problemId, String eventType, Instant occurredAt, int durationMs) {
	}
}
