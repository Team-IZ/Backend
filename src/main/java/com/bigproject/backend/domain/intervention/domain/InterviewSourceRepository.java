package com.bigproject.backend.domain.intervention.domain;

import java.util.UUID;

/**
 * 면담 근거({@code interview_source}) 생성.
 *
 * <h2>왜 AI 호출 전에 만들어야 하는가</h2>
 *
 * <p>AI가 만든 질문은 <b>어떤 근거에서 나왔는지를 반드시 밝혀야 한다</b> —
 * {@code interview_brief_item.interview_source_id}가 {@code UUID NOT NULL}이다. 그런데 AI는
 * DB를 모르므로 우리가 근거마다 행을 먼저 만들고 그 PK를 요청에 실어 보낸 뒤,
 * AI가 되돌려준 값이 <b>그 집합에 속하는지 대조</b>한다. 모델이 UUID를 지어내면 저장이 통째로
 * 실패하므로 이 대조가 마지막 방어선이다.
 *
 * <h2>정확히 하나만 채운다</h2>
 *
 * <p>{@code ck_interview_source_source_type}이
 * {@code num_nonnulls(candidate_reason_id, attempt_id, session_id, problem_id, problem_stage_id,
 * observation_note_id) = 1}을 강제한다. 근거 종류마다 컬럼 하나만 채우는 메서드를 따로 둔 이유다.
 *
 * <h2>원문을 복제하지 않는다</h2>
 *
 * <p>테이블 COMMENT: <i>"ProblemStage의 질문·힌트·각 답변 원문을 자동 복제하지 않고 접근 가능한
 * 요약·상태·행 버전 스냅샷만 보존합니다."</i> 스냅샷 컬럼은 "그때 무엇을 보고 판단했는가"를
 * 재현하기 위한 것이지 원문 사본이 아니다.
 */
public interface InterviewSourceRepository {

	/** 위험 사유 근거. {@code riskReasons[].sourceInterviewSourceId}가 된다. */
	UUID createFromCandidateReason(UUID interviewId, UUID candidateReasonId, UUID actorUserId);

	/**
	 * 시도 전체 근거. {@code comprehension.attemptInterviewSourceId}가 된다.
	 *
	 * <p>미응시({@code NOT_ATTENDED})처럼 problems가 비는 경우 <b>이 값만 남는다</b> —
	 * 그때도 "왜 안 봤는지"를 묻는 질문에 근거를 달 수 있어야 한다.
	 */
	UUID createFromAttempt(UUID interviewId, UUID attemptId, UUID actorUserId);

	/** 세션 근거. 세션이 열리지 않았으면 만들지 않는다. */
	UUID createFromSession(UUID interviewId, UUID sessionId, UUID actorUserId);

	/**
	 * 문제 단위 근거. {@code NOT_GENERATED}처럼 단계가 비는 문제에서 특히 필요하다 —
	 * 단계가 없으면 그 문제를 가리킬 다른 id가 없다.
	 */
	UUID createFromProblem(UUID interviewId, UUID problemId, UUID actorUserId);

	/** 단계 단위 근거. 질문 대부분이 여기에 달린다. */
	UUID createFromProblemStage(UUID interviewId, UUID problemStageId, UUID actorUserId);

	/** 관찰 메모 근거. 라포 질문이 이 값을 달 수 있어 null 빈도를 줄인다. */
	UUID createFromObservationNote(UUID interviewId, UUID noteId, UUID actorUserId);
}
