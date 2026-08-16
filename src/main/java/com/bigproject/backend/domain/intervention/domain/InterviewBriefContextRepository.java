package com.bigproject.backend.domain.intervention.domain;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 브리프 생성 요청에 실을 재료를 DB에서 읽는다.
 *
 * <p>AI는 DB에 접근하지 않으므로 {@code comprehension.problems[].stages[]}까지 전부 여기서
 * 조립한다. {@code interviewSourceId}는 아직 비운 채로 돌려주고,
 * {@link InterviewSourceRepository}가 근거마다 행을 만든 뒤 채운다 — 두 관심사를 나눈 이유는
 * 조립은 읽기이고 근거 생성은 쓰기라 트랜잭션 경계가 다르기 때문이다.
 */
public interface InterviewBriefContextRepository {

	/** 여는 말의 호칭·상황 재료. 케이스가 담당 밖이면 비어 있다. */
	Optional<InterviewBriefAiRequest.Target> findTarget(UUID candidateId);

	/** 위험 사유 전체. {@code reason_status='ACTIVE'}만 싣는다. */
	List<ReasonRow> findRiskReasons(UUID candidateId);

	/** 무효 확인 상태. 해당 회차 INITIAL 수행에서 읽는다. */
	Optional<InterviewBriefAiRequest.ValidityReview> findValidityReview(UUID candidateId);

	/** 수행·세션 헤더. {@code NOT_ATTENDED}면 세션이 없어 종료 사유가 null이다. */
	Optional<AttemptRow> findAttempt(UUID candidateId);

	/**
	 * 문제와 그 단계.
	 *
	 * <p>개념명은 <b>COALESCE 체인 4종</b>으로 정한다 — 문제 하나는 반드시 넷 중 하나로 귀결된다.
	 *
	 * <pre>
	 * TEACHES_CANONICAL_NAME       팀 공통 문제 → project_verification_concept → teaches
	 * CURRICULUM_EVIDENCE_TEACHES  개인 커밋 문제 → assessment_problem_reference(CURRICULUM_EVIDENCE) → teaches
	 * PROBLEM_TITLE                위 둘이 없을 때의 폴백
	 * UNAVAILABLE                  NOT_GENERATED — title까지 CHECK로 NULL이라 개념명이 없다
	 * </pre>
	 */
	List<ProblemRow> findProblems(UUID candidateId);

	List<StageRow> findStages(UUID candidateId);

	/** 이 교육생의 지난 면담들. 첫 면담이면 빈 목록이다. */
	List<PriorInterviewRow> findPriorInterviews(UUID traineeUserId, UUID currentInterviewId);

	/**
	 * 관찰 메모.
	 *
	 * <p>🔴 <b>권한 필터링을 여기서 한다.</b> AI는 {@code visibility}로 거르지 않는다 —
	 * 어떤 메모를 브리프에 쓸 수 있는지는 org/cohort 테넌시와 배정 유효성이 걸린 판단이고
	 * 그 맥락은 백엔드에만 있다.
	 */
	List<ObservationNoteRow> findObservationNotes(UUID candidateId, UUID managerUserId);

	record ReasonRow(
			UUID candidateReasonId,
			String reasonCode,
			String evaluationStatus,
			String notApplicableReasonCode,
			String reasonSummary,
			java.time.Instant detectedAt,
			UUID sourceProblemStageId) {
	}

	record AttemptRow(
			UUID attemptId,
			UUID sessionId,
			String attemptType,
			String attemptStatus,
			String terminalReasonCode,
			String sessionEndReasonCode) {
	}

	record ProblemRow(
			UUID problemId,
			int problemNo,
			String conceptName,
			String conceptNameSource,
			String problemScope,
			String generationStatus,
			String notGeneratedReasonCode,
			String bestSuccessStage,
			String codeLanguage,
			String sourcePath,
			Integer sourceLineStart,
			Integer sourceLineEnd,
			String sourceSnippetKey,
			String codeSnippetHash) {
	}

	record StageRow(
			UUID problemStageId,
			UUID problemId,
			String axisCode,
			String status,
			String questionText,
			String questionAnswerText,
			Integer questionScore,
			Boolean questionPassed,
			String firstHintText,
			String firstHintAnswerText,
			Integer firstHintScore,
			Boolean firstHintPassed,
			String secondHintText,
			String secondHintAnswerText,
			Integer secondHintScore,
			Boolean secondHintPassed,
			boolean flagged) {
	}

	record PriorInterviewRow(
			UUID interviewId,
			java.time.Instant completedAt,
			String resultSummary,
			List<InterviewBriefAiRequest.PriorInterviewActivity> activities,
			List<String> askedQuestions) {
	}

	record ObservationNoteRow(
			UUID noteId,
			java.time.Instant occurredAt,
			String content,
			String visibility) {
	}
}
