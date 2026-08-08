package com.bigproject.backend.global.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * AI 응답 본문의 {@code aiUsage[]} 한 줄. LLM 호출 1회 = 이 레코드 1개다.
 *
 * <p><b>AI가 보내는 것과 DB가 요구하는 것이 다르다.</b> AI는 담당 경계상(AI 저장소
 * {@code app/schemas/usage.py} C-3) 토큰·모델·지연·상태만 보낸다 — 단가·비용은 모델을 고르는
 * 쪽인 백엔드가 계산하고, 귀속 정보({@code org_id}·{@code cohort_id} 등)는 AI가 알 수 없다.
 * 그래서 이 레코드를 그대로 저장할 수 없고 {@link AiUsageRecorder}가 나머지를 채운다.
 *
 * <p>⚠️ 옛 응답 헤더 방식({@code X-Ai-Usage-*} 7개)은 2026-08-07에 폐기됐다. 다섯 엔드포인트가
 * 모두 <b>본문 {@code aiUsage[]}</b>로 통일됐으므로 헤더를 읽으려 하지 않는다.
 *
 * <p>{@link JsonIgnoreProperties}로 모르는 필드를 흘려보낸다 — AI가 필드를 먼저 추가해도
 * 백엔드가 역직렬화에서 깨지지 않아야 한다(원장이 끊기면 청구가 끊긴다).
 *
 * @param featureCode    {@code CODE_ANALYSIS} 등 6종. 백엔드 {@code AiUsage.FeatureCode}와 글자까지 같다.
 * @param modelCode      AI가 호출에 실제로 쓴 provider 모델 문자열. 화면에서 고른 값이 아니다 —
 *                       백엔드는 이 값을 {@code ai_model.provider_model_code}로 조회해야 한다.
 * @param contextType    {@code ANALYSIS_JOB} 등 5종. 백엔드 {@code AiUsage.ContextType} 12종의 부분집합이다.
 * @param contextId      처리한 업무 엔터티의 PK. ⚠️ {@code REPORT_SNAPSHOT}·{@code CURRICULUM_ANALYSIS}는
 *                       AI가 그 PK를 받은 적이 없어 <b>AI 내부 jobId</b>가 들어온다 —
 *                       {@link AiUsageRecorder}가 저장 시점에 실제 PK로 바꾼다.
 * @param idempotencyKey DB에서 전역 UNIQUE다. 같은 값이 두 번 오면 두 번째 INSERT가 거부된다.
 * @param status         {@code SUCCEEDED} · {@code FAILED} · {@code PARTIAL}
 * @param failureCode    {@code TIMEOUT}·{@code RATE_LIMITED}·{@code PROVIDER_ERROR}·
 *                       {@code INVALID_JSON}·{@code CONTEXT_OVERFLOW}. 성공이면 null이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiUsageEnvelope(
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
