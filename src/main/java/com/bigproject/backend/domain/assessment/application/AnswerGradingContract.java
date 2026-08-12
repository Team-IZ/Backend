package com.bigproject.backend.domain.assessment.application;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * AI {@code POST /api/v0/sessions/{sessionId}/answers}의 요청·응답 계약.
 *
 * <p><b>AI는 세션 상태를 들고 있지 않다.</b> 그래서 채점에 필요한 것이 매 요청에 전부 실려야 한다 —
 * 문제 3개(각 4단계 + 힌트 8개), 지금까지 확정된 턴 전부, 그리고 커서. 전부 DB에서 재구성되므로
 * 우리 API는 {@code answerText} 하나만 받는다(요구사항 2).
 *
 * <p>필드 이름은 AI OpenAPI 그대로다. 이름을 바꾸면 계약이 조용히 깨지므로 여기서만 camelCase를 쓴다.
 */
public final class AnswerGradingContract {

	private AnswerGradingContract() {
	}

	/** {@code null} 필드를 빼고 보낸다 — AI가 선택 필드를 "없음"으로 읽는다. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AnswerSubmit(
			String clientRequestId,
			String answerText,
			List<Problem> problems,
			List<TranscriptTurn> transcript,
			Cursor cursor,
			String providerModelCode
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Problem(
			UUID problemId,
			int problemNo,
			String status,
			String problemType,
			BigDecimal priority,
			String questionFocusItemId,
			String title,
			String snippetKey,
			String codeLanguage,
			String sourcePath,
			int lineStart,
			int lineEnd,
			String codeSnippet,
			String contentHash,
			String evidenceHash,
			Integer extractorVersion,
			UUID teachId,
			List<ProblemReference> references,
			List<ProblemStage> stages
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProblemReference(
			String referenceType,
			Integer displayOrder,
			String path,
			Integer lineStart,
			Integer lineEnd,
			String axisCode,
			UUID teachId,
			String evidenceHash
	) {
	}

	/** 단계 하나. {@code stages}는 <b>4개 고정</b>이다(AI 스키마 minItems=maxItems=4). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProblemStage(String axisCode, String questionText, Boolean flagged, List<Hint> hints) {
	}

	public record Hint(int hintLevel, String hintText) {
	}

	/** 확정된 문답 한 턴. {@code hintsUsed}가 {@code problem_stage}의 어느 슬롯에서 왔는지를 말한다. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TranscriptTurn(
			UUID problemId,
			String axisCode,
			String questionText,
			String answerText,
			String answeredAt,
			int score,
			boolean passed,
			int hintsUsed,
			String hintText
	) {
	}

	/**
	 * 문답이 서 있는 자리. 요청과 응답에 같은 모양으로 오간다.
	 *
	 * <p>{@code mac}은 보내지 않는다 — AI 문서가 "생략하면 무검증으로 신뢰(점진 도입 단계)"라고 했고
	 * 저장할 컬럼이 없다. 도입하려면 {@code assessment_session}에 컬럼을 하나 더해야 한다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Cursor(UUID problemId, String axisCode, Integer hintsUsed, String mac) {
	}

	// ── 응답 ──

	/**
	 * 채점 결과. <b>transcript를 통째로 돌려주지 않는다</b> — 이번 턴({@code turn})만 오므로 그것만
	 * 슬롯에 붙여 저장한다.
	 */
	public record AnswerResult(
			UUID sessionId,
			String state,
			TranscriptTurn turn,
			Cursor cursor,
			Question current,
			Progress progress,
			String terminationReason,
			String endedLevel,
			List<AiUsage> aiUsage
	) {
	}

	public record Question(
			UUID problemId,
			String axisCode,
			Integer sequenceNo,
			String questionText,
			String hintText,
			Integer hintsUsed
	) {
	}

	public record Progress(int problemIndex, int problemTotal) {
	}

	/** 사용량. {@code ai_usage} 적재는 기존 기록기가 맡으므로 여기서는 받아만 둔다. */
	public record AiUsage(
			String featureCode,
			String modelCode,
			String contextType,
			String contextId,
			String requestId,
			String traceId,
			String idempotencyKey,
			Long inputTokenCount,
			Long outputTokenCount,
			Long cachedTokenCount,
			String status,
			String failureCode,
			Integer latencyMs,
			String occurredAt
	) {
	}
}
