package com.bigproject.backend.domain.codeanalysis.application;

import java.time.Instant;

/**
 * 폴링 응답의 {@code aiUsage[]} 한 건. AI가 이 분석을 처리하며 실제로 부른 LLM 호출 1회다.
 *
 * <p>한 분석이 20건 넘게 만든다(실측: CODE_ANALYSIS 2 + CODE_SESSION 20). 이 값이
 * {@code monthly_ai_budget} 집행과 기관별 비용 정산의 근거이므로 <b>조용히 비면 예산이 새는 쪽으로
 * 틀린다.</b>
 *
 * <p>AI가 주지 않는 것: {@code org_id}·{@code trigger_type}·{@code attribution_status}·
 * {@code pricing_status}와 단가·비용. 전부 우리 정책이라 적재하는 자리에서 채운다.
 */
public record AiUsageEntry(
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
		Instant occurredAt
) {
}
