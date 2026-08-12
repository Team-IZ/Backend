package com.bigproject.backend.domain.reporting.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * AI {@code POST /reports} 요청 본문. AI 저장소 {@code app/schemas/report.py:ReportRequest}와 1:1이다.
 *
 * <p>보고서는 <b>문제 하나당 1건</b>이다({@code problemId}가 키). 회차 전체가 아니라 문제 단위로
 * 부르는 이유는 AI 쪽 멱등키가 {@code {problemId}:{scoreRunId}}이기 때문이다 — 같은 문제를
 * 다시 채점하면 scoreRunId가 달라져 새 호출이 되고, 같은 채점을 재전송하면 LLM을 다시 부르지 않는다.
 *
 * <p>{@code transcript}·{@code analysisDocuments}·{@code teaches}를 {@link JsonNode}로 두는 이유:
 * AI 쪽 타입이 {@code list[dict[str, Any]]}로 열려 있어 고정 스키마가 없다. 백엔드가 자기 DTO로
 * 좁히면 AI가 필드를 추가할 때마다 조용히 값이 잘려 나간다 — 잘린 채로 프롬프트에 들어가면
 * 결과가 나빠지는데 어디서 잘렸는지 알 수 없다.
 *
 * @param providerModelCode 모델을 고르는 주체가 백엔드·프론트라 여기서 지정한다.
 *                          null이면 AI 서버 기본값을 쓰고, 그 값이 {@code aiUsage.modelCode}로 돌아온다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportGenerationRequest(
		UUID problemId,
		Integer problemNo,
		UUID sessionId,
		String scoreRunId,
		String providerModelCode,
		List<JsonNode> transcript,
		List<JsonNode> analysisDocuments,
		List<JsonNode> teaches
) {

	/**
	 * AI 멱등키. 명세가 {@code {problemId}:{scoreRunId}}로 정한 형식이다.
	 *
	 * <p>{@code ai_usage.idempotency_key}가 전역 UNIQUE라 요청 단위 키가 요청마다 달라야 한다 —
	 * problemId만 쓰면 재채점 때 충돌해서 두 번째 원장 행이 통째로 거부된다.
	 */
	public String idempotencyKey() {
		return problemId + ":" + (scoreRunId == null ? "" : scoreRunId);
	}

	/**
	 * {@code scoreRunId}만 채운 사본.
	 *
	 * <p>이 값({@code report_generation_run.generation_run_id})은 실행 행을 저장한 뒤에야 정해지는데,
	 * 요청 본문은 그 전에 다 만들어 둬야 한다 — 본문을 만들다 실패하면 실행 행을 남기지 않는 편이
	 * 맞기 때문이다(재시도 상한만 깎고 아무것도 못 한다). 그래서 두 단계로 나눈다.
	 *
	 * <p>🔴 <b>보내기 전에 반드시 채워야 한다.</b> 비어 있으면 위 멱등키가 문제당 상수가 되어,
	 * 재생성 때 AI가 처음 jobId를 그대로 돌려주고 {@code uq_report_generation_item_external_job_id}가
	 * 두 번째 item을 거부한다.
	 */
	public ReportGenerationRequest withScoreRunId(String scoreRunId) {
		return new ReportGenerationRequest(problemId, problemNo, sessionId, scoreRunId,
				providerModelCode, transcript, analysisDocuments, teaches);
	}
}
