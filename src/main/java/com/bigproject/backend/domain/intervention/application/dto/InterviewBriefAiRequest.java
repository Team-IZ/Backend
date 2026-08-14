package com.bigproject.backend.domain.intervention.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST {AI}/api/v0/interview-briefs} 요청 본문.
 *
 * <p>AI 저장소 {@code app/schemas/interview_brief.py}의 {@code InterviewBriefRequest}와
 * 1:1로 맞춘다. 필드 이름이 곧 계약이므로 <b>임의로 줄이거나 바꾸지 않는다</b> —
 * 모르는 값이 오면 AI가 422로 거절한다.
 *
 * <h2>AI는 DB에 접근하지 않는다</h2>
 *
 * <p>그래서 {@code comprehension.problems[].stages[]}까지 내려가는 이 페이로드를
 * <b>전부 백엔드가 조립한다.</b> 이 도메인 구현 작업량의 대부분이 여기다.
 *
 * <h2>코드 원문은 보내지 않는다</h2>
 *
 * <p>{@link CodeContext}는 좌표와 키·해시만 담는다. {@code assessment_problem}에 코드 원문이
 * 아예 없기도 하고(원문은 {@code submission.code_snippets} JSONB에 있다), GitHub 접근 주체가
 * 이미 AI 서버라 백엔드가 되돌려주는 건 중복이면서 코드 외부 전송 범위만 넓힌다.
 */
public record InterviewBriefAiRequest(
		UUID briefId,
		Target target,
		BriefContext briefContext,
		List<RiskReason> riskReasons,
		ValidityReview validityReview,
		Comprehension comprehension,
		List<PriorInterview> priorInterviews,
		List<ObservationNote> observationNotes) {

	/** 여는 말의 호칭·상황 설명 재료. */
	public record Target(
			String userName,
			String className,
			String projectName,
			String projectCategory,
			String roundName,
			String analysisRoleCode) {
	}

	/**
	 * @param isFirstInterview true면 AI가 <b>라포 형성용 도입 질문을 반드시 넣고</b>
	 *                         항목 하한이 4개에서 6개로 올라간다
	 */
	public record BriefContext(String briefType, boolean isFirstInterview) {
	}

	/**
	 * @param evaluationStatus {@code NOT_APPLICABLE}·{@code UNAVAILABLE}이면 AI가
	 *                         "문제 없음"이 아니라 <b>"이번엔 판단 불가"</b>로 서술한다
	 * @param sourceInterviewSourceId ★ 이 사유로 질문을 만들면 AI가 이 값을 그대로 실어 보낸다
	 */
	public record RiskReason(
			String reasonCode,
			String evaluationStatus,
			String notApplicableReasonCode,
			String reasonSummary,
			Instant detectedAt,
			UUID sourceProblemStageId,
			UUID sourceInterviewSourceId) {
	}

	public record ValidityReview(
			String status,
			String triggerReasonCode,
			String decisionReasonCode,
			String decisionNote) {
	}

	/**
	 * @param attemptInterviewSourceId ★ 시도 전체(예: 미응시 사실)를 근거로 삼을 때 쓴다.
	 *                                 {@code NOT_ATTENDED}면 problems가 비어 이 값만 남는다
	 * @param sessionInterviewSourceId ★ 세션이 열리지 않았으면 null
	 */
	public record Comprehension(
			String attemptType,
			String attemptStatus,
			String terminalReasonCode,
			String sessionEndReasonCode,
			UUID attemptInterviewSourceId,
			UUID sessionInterviewSourceId,
			List<ProblemComprehension> problems) {
	}

	/**
	 * @param conceptName       ★ 질문에서 L2 같은 내부 코드 대신 이 이름을 쓴다.
	 *                          {@code conceptNameSource=UNAVAILABLE}이면 null이다
	 * @param conceptNameSource 개념명이 어느 경로에서 나왔는지. {@code TEACHES_CANONICAL_NAME}
	 *                          외에는 검증된 개념명이 아니라 AI가 단정적으로 서술하지 않는다
	 */
	public record ProblemComprehension(
			int problemNo,
			String conceptName,
			String conceptNameSource,
			String problemScope,
			String generationStatus,
			String notGeneratedReasonCode,
			String bestSuccessStage,
			CodeContext codeContext,
			UUID interviewSourceId,
			List<ComprehensionStage> stages) {
	}

	/** 좌표와 식별자만. 원문은 담지 않는다(클래스 docblock 참고). */
	public record CodeContext(
			String language,
			String path,
			int lineStart,
			int lineEnd,
			String snippetKey,
			String snippetHash) {
	}

	/**
	 * 질문·힌트1·힌트2 세 슬롯. <b>답한 만큼만 채워진다</b> — 힌트를 안 봤으면 그 슬롯이 전부 null이다.
	 *
	 * @param isFlagged true면 질문 자체가 이상해 재생성에도 실패한 단계다. AI가 근거로 삼지 않는다
	 */
	public record ComprehensionStage(
			UUID problemStageId,
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
			boolean isFlagged,
			UUID interviewSourceId) {
	}

	/**
	 * @param askedQuestions ★ 지난 회차에 실제로 던진 질문 원문. AI가 같은 질문을 반복하지 않고
	 *                       후속 질문으로 발전시킨다
	 */
	public record PriorInterview(
			Instant completedAt,
			String resultSummary,
			List<PriorInterviewActivity> activities,
			List<String> askedQuestions) {
	}

	public record PriorInterviewActivity(String content, String nextAction, Instant occurredAt) {
	}

	/**
	 * @param visibility AI는 이 값으로 <b>필터링하지 않는다</b> — 어떤 메모를 브리프에 쓸 수 있는지는
	 *                   테넌시·배정 유효성이 걸린 판단이고 그 맥락은 백엔드에만 있다.
	 *                   백엔드가 요청 시점에 이미 걸러 보낸다
	 */
	public record ObservationNote(
			Instant occurredAt,
			String content,
			UUID interviewSourceId,
			String visibility) {
	}
}
