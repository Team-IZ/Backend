package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AiUsageEntry;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * AI가 보고한 LLM 호출 사용량을 {@code ai_usage}에 적재한다.
 *
 * <h2>AI가 주지 않는 값을 우리가 채운다</h2>
 *
 * <p>응답의 {@code aiUsage[]}는 토큰 수·지연·모델까지만 준다. {@code org_id}·{@code trigger_type}·
 * {@code attribution_status}·{@code pricing_status}와 단가·비용은 전부 우리 정책이라 여기서 붙인다.
 *
 * <h2>feature_code 는 AI 가 준 값을 그대로 쓴다</h2>
 *
 * <p>실측 응답 22건 중 20건이 {@code CODE_SESSION} 이고, 그것이 맞다(2026-08-09 확정). 두 기능은
 * 이렇게 갈린다.
 *
 * <pre>
 * CODE_ANALYSIS : 코드 분석 자체
 * CODE_SESSION  : 문답 질문 생성과 답변 채점
 * </pre>
 *
 * <p>질문 생성이 분석 배치 <b>안에서</b> 일어나긴 하지만 기능으로는 문답 쪽이다. 그래서 job 종류로
 * 덮어쓰지 않는다 — 덮으면 기능별 비용이 전부 분석으로 잡혀 문답 비용을 볼 수 없게 된다.
 *
 * <p>🔴 그 결과 <b>{@code CODE_SESSION} 은 {@code tier_code}·{@code tier_policy_id} 가 없으면
 * 저장되지 않는다</b>({@code ck_ai_usage_feature_code_2}). 22건 중 20건이라 이걸 빠뜨리면 사용량이
 * 거의 전부 사라진다. {@code organization_policy.code_session_tier_code} 로 기관의 티어를 읽어
 * 현행 정책 행을 붙인다.
 *
 * <h2>미등록 모델은 건너뛴다</h2>
 *
 * <p>{@code ai_usage.model_code}는 {@code ai_model}을 참조하는 FK다. 카탈로그에 없는 모델이면
 * INSERT가 실패하고, 한 트랜잭션 안이라 <b>다른 적재까지 함께 롤백된다.</b> 그래서 조인으로 걸러
 * 넣고, 걸러진 건수를 경고로 남긴다 — 그 로그가 "모델 카탈로그에 빠진 모델이 있다"는 신호다.
 * 애초에 이런 일이 없도록 요청 시 {@code providerModelCode}를 항상 명시한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcAiUsageRecorder {

	private final JdbcTemplate jdbc;

	/** @return 실제로 적재된 사용량 행 수 */
	public int record(AnalysisJob job, List<AiUsageEntry> usages) {
		if (usages == null || usages.isEmpty()) {
			return 0;
		}
		int inserted = 0;
		for (AiUsageEntry usage : usages) {
			inserted += insertOne(job, usage);
		}
		if (inserted < usages.size()) {
			// 대부분 미등록 모델이거나 멱등키 중복이다. 전자는 예산 집계가 새는 것이라 확인이 필요하다.
			log.warn("사용량 일부가 적재되지 않았다: jobId={}, 응답={}, 적재={}",
					job.getJobId(), usages.size(), inserted);
		}
		return inserted;
	}

	/**
	 * 사용량 1건.
	 *
	 * <p><b>{@code trigger_type}을 {@code execution_no}로 가른다.</b> 첫 실행은 제출 이벤트가 부른
	 * 것이고({@code EVENT}), 2회차부터는 안전망 배치가 부른 재시도다({@code BATCH}). 우리 디스패치
	 * 구조가 정확히 그렇게 갈리므로 별도 컬럼 없이 사실대로 기록할 수 있다.
	 *
	 * <p>{@code ON CONFLICT (idempotency_key)}가 필요한 이유: {@code uq_ai_usage_idempotency_key}가
	 * 전역 UNIQUE인데 폴링은 같은 SUCCEEDED 응답을 두 번 읽을 수 있다(트랜잭션 중간에 죽는 등).
	 * 그때 예외로 터지면 이미 적재된 나머지까지 롤백된다.
	 */
	private int insertOne(AnalysisJob job, AiUsageEntry usage) {
		return jdbc.update("""
				INSERT INTO ai_usage (
				    org_id, model_code, actor_user_id, cohort_id, class_id, project_id,
				    feature_code, context_type, context_id, trigger_type,
				    tier_code, tier_policy_id,
				    attribution_status, unallocated_reason_code, class_attribution_status,
				    request_id, trace_id, idempotency_key,
				    input_token_count, output_token_count, cached_token_count,
				    pricing_status, input_unit_price, output_unit_price, cached_input_unit_price,
				    currency_code, estimated_cost, status, failure_code, latency_ms, occurred_at)
				SELECT ?, m.model_code, NULL, ctx.cohort_id, NULL, ctx.project_id,
				       ?, ?, ?, ?,
				       CASE WHEN ? = 'CODE_SESSION' THEN op.code_session_tier_code END,
				       CASE WHEN ? = 'CODE_SESSION' THEN tp.tier_policy_id END,
				       CASE WHEN ctx.cohort_id IS NOT NULL AND ctx.project_id IS NOT NULL
				            THEN 'ALLOCATED' ELSE 'UNALLOCATED' END,
				       CASE WHEN ctx.cohort_id IS NULL OR ctx.project_id IS NULL
				            THEN 'COHORT_UNRESOLVED' END,
				       'NOT_APPLICABLE',
				       ?, ?, ?, ?, ?, ?,
				       CASE WHEN m.input_unit_price IS NOT NULL AND m.output_unit_price IS NOT NULL
				                 AND m.currency_code IS NOT NULL THEN 'PRICED' ELSE 'UNPRICED' END,
				       m.input_unit_price, m.output_unit_price, m.cached_input_unit_price, m.currency_code,
				       CASE WHEN m.input_unit_price IS NOT NULL AND m.output_unit_price IS NOT NULL
				                 AND m.currency_code IS NOT NULL
				            THEN round((? * m.input_unit_price + ? * m.output_unit_price
				                      + ? * coalesce(m.cached_input_unit_price, 0))
				                      / nullif(m.price_unit_token_count, 0), 6) END,
				       ?, ?, ?, ?
				  FROM ai_model m
				  JOIN organization_policy op ON op.org_id = ?
				  LEFT JOIN platform_ai_tier_model_policy tp
				    ON tp.feature_code = 'CODE_SESSION'
				   AND tp.tier_code = op.code_session_tier_code
				   AND tp.status = 'ACTIVE'
				  CROSS JOIN LATERAL (
				     SELECT c.cohort_id, pm.project_id
				       FROM team_membership tm
				       JOIN project_membership pm ON pm.project_membership_id = tm.project_membership_id
				        AND pm.status = 'ACTIVE'
				       JOIN class c ON c.class_id = pm.class_id
				      WHERE tm.team_id = ? AND tm.to_at IS NULL LIMIT 1) ctx
				 WHERE m.model_code = ?
				ON CONFLICT (idempotency_key) DO NOTHING
				""",
				job.getOrgId(),
				usage.featureCode(), usage.contextType(), usage.contextId(), triggerTypeOf(job),
				usage.featureCode(), usage.featureCode(),
				usage.requestId(), usage.traceId(), usage.idempotencyKey(),
				zeroIfNull(usage.inputTokenCount()), zeroIfNull(usage.outputTokenCount()),
				zeroIfNull(usage.cachedTokenCount()),
				zeroIfNull(usage.inputTokenCount()), zeroIfNull(usage.outputTokenCount()),
				zeroIfNull(usage.cachedTokenCount()),
				usage.status(), usage.failureCode(),
				usage.latencyMs() == null ? 0 : usage.latencyMs(),
				Timestamp.from(usage.occurredAt() == null ? Instant.now() : usage.occurredAt()),
				job.getOrgId(), job.getTeamId(), usage.modelCode());
	}

	/** 첫 실행은 제출 이벤트, 2회차부터는 안전망 배치의 재시도다. */
	private static String triggerTypeOf(AnalysisJob job) {
		return job.getExecutionNo() != null && job.getExecutionNo() > 1 ? "BATCH" : "EVENT";
	}

	private static long zeroIfNull(Long value) {
		return value == null ? 0L : value;
	}
}
