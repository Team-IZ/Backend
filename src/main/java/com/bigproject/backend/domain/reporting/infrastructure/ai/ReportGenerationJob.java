package com.bigproject.backend.domain.reporting.infrastructure.ai;

import com.bigproject.backend.global.ai.AiUsageEnvelope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * AI 보고서 job의 접수 응답과 상태 응답.
 *
 * <p>AI의 {@code POST /reports}는 <b>202 + jobId</b>만 주고 실제 생성은 백그라운드에서 돈다.
 * 그래서 결과를 얻으려면 {@code GET /reports/{jobId}}를 폴링해야 한다 — 면담 브리프가 동기
 * 계약인 것과 다르다.
 */
public final class ReportGenerationJob {

	private ReportGenerationJob() {
	}

	/**
	 * {@code POST /reports} 202 응답. AI {@code ReportAccepted}와 1:1.
	 *
	 * <p>같은 멱등키가 다시 오면 <b>처음 jobId를 그대로</b> 돌려주고 LLM을 다시 부르지 않는다.
	 * 즉 이 응답의 jobId가 새 것인지 재사용인지는 구분되지 않으며, 구분할 필요도 없다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Accepted(String jobId, String status) {
	}

	/**
	 * {@code GET /reports/{jobId}} 응답. AI {@code ReportJobStatus}와 1:1.
	 *
	 * @param status  {@code QUEUED} · {@code RUNNING} · {@code SUCCEEDED} · {@code PARTIAL} · {@code FAILED}
	 * @param result  {@code SUCCEEDED}·{@code PARTIAL}일 때만 채워진다. {@link JsonNode}로 두는 이유는
	 *                이 JSON이 결국 {@code report_snapshot.summary_payload}(JSONB)로 들어가기 때문이다 —
	 *                DTO로 좁혔다가 다시 직렬화하면 모르는 필드가 소리 없이 사라진다.
	 * @param aiUsage 이 job이 태운 LLM 호출 기록. <b>실패해도 채워진다</b> — 태운 토큰은 남긴다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Status(
			String jobId,
			String problemId,
			String sessionId,
			String status,
			String failureReason,
			Instant startedAt,
			Instant completedAt,
			JsonNode result,
			List<AiUsageEnvelope> aiUsage
	) {

		/** 더 기다려도 바뀌지 않는 상태인가. 폴링 종료 조건이다. */
		public boolean isTerminal() {
			return "SUCCEEDED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
		}

		public boolean isFailed() {
			return "FAILED".equals(status);
		}
	}
}
