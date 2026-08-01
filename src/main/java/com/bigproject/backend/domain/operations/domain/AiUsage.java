package com.bigproject.backend.domain.operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ai_usage 테이블 매핑 엔티티. 기관별 AI 호출 1건마다 남는 토큰 사용량·비용 원장(append-only 로그).
 * 이 도메인에서는 월별 AI 비용 사용량 집계(findUsage) 조회 전용으로 사용하며,
 * 실제 사용량 적재는 AI 호출을 수행하는 다른 도메인(채점/세션 등)의 책임이다.
 */
@Getter
@Entity
@Table(name = "ai_usage")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiUsage {

	@Id
	@UuidGenerator
	@Column(name = "usage_id", updatable = false, nullable = false)
	private UUID usageId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	// 같은 operations 도메인 소속이라 AiModel과는 실제 연관관계로 매핑해, 사용량 집계 시 모델 표시명 등을 조인해 가져온다.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "model_id", nullable = false, updatable = false)
	private AiModel model;

	@Column(name = "model_instance_id", nullable = false, updatable = false)
	private UUID modelInstanceId;

	@Column(name = "actor_user_id", updatable = false)
	private UUID actorUserId;

	// DB CHECK: feature_code IN ('GRADING', 'SESSION_DIALOG', 'SUMMARY_DRAFT', 'CURRICULUM_ANALYSIS')
	@Enumerated(EnumType.STRING)
	@Column(name = "feature_code", nullable = false, updatable = false, length = 100)
	private FeatureCode featureCode;

	@Column(name = "cohort_id", updatable = false)
	private UUID cohortId;

	@Column(name = "class_id", updatable = false)
	private UUID classId;

	@Column(name = "project_id", updatable = false)
	private UUID projectId;

	@Column(name = "context_type", nullable = false, updatable = false, length = 100)
	private String contextType;

	@Column(name = "context_id", updatable = false)
	private String contextId;

	@Column(name = "trigger_type", nullable = false, updatable = false, length = 30)
	private String triggerType;

	@Column(name = "tier_code", updatable = false, length = 30)
	private String tierCode;

	@Column(name = "tier_policy_id", updatable = false)
	private UUID tierPolicyId;

	@Column(name = "grading_policy_id", updatable = false)
	private UUID gradingPolicyId;

	@Column(name = "calibration_version_id", updatable = false)
	private UUID calibrationVersionId;

	@Column(name = "attribution_status", nullable = false, updatable = false, length = 30)
	private String attributionStatus;

	@Column(name = "unallocated_reason_code", updatable = false, length = 100)
	private String unallocatedReasonCode;

	@Column(name = "class_attribution_status", nullable = false, updatable = false, length = 30)
	private String classAttributionStatus;

	@Column(name = "class_unallocated_reason_code", updatable = false, length = 100)
	private String classUnallocatedReasonCode;

	@Column(name = "request_id", nullable = false, updatable = false)
	private String requestId;

	@Column(name = "trace_id", nullable = false, updatable = false)
	private String traceId;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Column(name = "input_token_count", nullable = false, updatable = false)
	private Long inputTokenCount;

	@Column(name = "output_token_count", nullable = false, updatable = false)
	private Long outputTokenCount;

	@Column(name = "cached_token_count", nullable = false, updatable = false)
	private Long cachedTokenCount;

	@Column(name = "pricing_status", nullable = false, updatable = false, length = 30)
	private String pricingStatus;

	@Column(name = "input_unit_price", updatable = false, precision = 18, scale = 6)
	private BigDecimal inputUnitPrice;

	@Column(name = "output_unit_price", updatable = false, precision = 18, scale = 6)
	private BigDecimal outputUnitPrice;

	@Column(name = "cached_input_unit_price", updatable = false, precision = 18, scale = 6)
	private BigDecimal cachedInputUnitPrice;

	@Column(name = "currency_code", updatable = false, length = 3)
	private String currencyCode;

	@Column(name = "estimated_cost", updatable = false, precision = 18, scale = 6)
	private BigDecimal estimatedCost;

	// 실제 청구 확정 전에는 null일 수 있어(정산 지연 등), 표시 시에는 actualCost가 있으면 우선 사용하고 없으면 estimatedCost를 쓴다.
	@Column(name = "actual_cost", updatable = false, precision = 18, scale = 6)
	private BigDecimal actualCost;

	// DB CHECK: status IN ('SUCCEEDED', 'FAILED', 'PARTIAL')
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false, length = 30)
	private Status status;

	@Column(name = "failure_code", updatable = false, length = 100)
	private String failureCode;

	@Column(name = "latency_ms", nullable = false, updatable = false)
	private Integer latencyMs;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 실제 비용이 확정됐으면 실제 비용을, 아니면 추정 비용을 반환한다. */
	public BigDecimal resolveCost() {
		return actualCost != null ? actualCost : estimatedCost != null ? estimatedCost : BigDecimal.ZERO;
	}

	public enum FeatureCode {
		CODE_ANALYSIS, CURRICULUM_ANALYSIS, QUESTION_GENERATION, ANSWER_GRADING, SUMMARY_DRAFT
	}

	public enum Status {
		SUCCEEDED, FAILED, PARTIAL
	}
}
