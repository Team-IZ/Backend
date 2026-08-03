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

//	/**
//	 * 실제 호출에 사용된 모델 인스턴스(ai_model_instance). v06 신규 NOT NULL FK다.
//	 * 이 도메인은 조회 전용이라 인스턴스 엔티티까지 만들지 않고 원시 UUID만 보관한다.
//	 */
//	@Column(name = "model_instance_id", nullable = false, updatable = false)
//	private UUID modelInstanceId;

	@Column(name = "actor_user_id", updatable = false)
	private UUID actorUserId;

	/*
	 * v06 신규 귀속 FK 3종. OP-06 ⑤ 비용 탭의 기수별·반별 비용을 이 컬럼으로 직접 집계한다
	 * (이전에는 컬럼이 없어 OperationsSchemaPending에서 빈 목록으로 대체하고 있었다).
	 * class_id는 "호출 시점"의 반이며 이후 반 배정이 바뀌어도 갱신하지 않는다.
	 */
	@Column(name = "cohort_id", updatable = false)
	private UUID cohortId;

	@Column(name = "class_id", updatable = false)
	private UUID classId;

	@Column(name = "project_id", updatable = false)
	private UUID projectId;

	// DB CHECK: feature_code IN ('CODE_ANALYSIS','CURRICULUM_ANALYSIS','QUESTION_GENERATION','ANSWER_GRADING','SUMMARY_DRAFT')
	@Enumerated(EnumType.STRING)
	@Column(name = "feature_code", nullable = false, updatable = false, length = 100)
	private FeatureCode featureCode;

	/*
	 * v06에서 source_type/source_id가 context_type/context_id로 대체됐다.
	 * context_id는 다형 참조라 물리 FK가 없고, 업무 맥락이 확정되지 않은 실패 기록은 NULL을 허용한다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "context_type", nullable = false, updatable = false, length = 100)
	private ContextType contextType;

	@Column(name = "context_id", updatable = false)
	private String contextId;

	// DB CHECK: trigger_type IN ('USER','SCHEDULED','EVENT','RETRY','BATCH')
	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, updatable = false, length = 30)
	private TriggerType triggerType;

	/** 질문 생성·요약 실행 시 기관이 선택한 티어 스냅샷. 그 외 기능은 NULL이라 화면에서 `플랫폼 고정`으로 표시한다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "tier_code", updatable = false, length = 30)
	private AiTier tierCode;

	/*
	 * 실행 당시 적용된 플랫폼 정책 스냅샷. 나중에 정책이 바뀌어도 이 호출이 어떤 기준으로 실행됐는지 재현할 수 있다.
	 * 질문 생성·요약은 티어 정책을, 답변 채점은 채점 모델 정책과 캘리브레이션 버전을 남긴다(해당 없으면 NULL).
	 */
	@Column(name = "tier_policy_id", updatable = false)
	private UUID tierPolicyId;

	@Column(name = "grading_policy_id", updatable = false)
	private UUID gradingPolicyId;

	@Column(name = "calibration_version_id", updatable = false)
	private UUID calibrationVersionId;

	/*
	 * 비용 귀속 상태. 기수·프로젝트를 확정하지 못한 호출을 0으로 숨기지 않고 `미귀속`으로 드러내기 위한 값이다
	 * (목업 SA-02: "집계 실패를 0으로 보여주지 않는다").
	 * 미귀속 사유 코드는 왜 귀속에 실패했는지를 남겨 운영자가 원인을 좁힐 수 있게 한다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "attribution_status", nullable = false, updatable = false, length = 30)
	private AttributionStatus attributionStatus;

	@Column(name = "unallocated_reason_code", updatable = false, length = 100)
	private String unallocatedReasonCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "class_attribution_status", nullable = false, updatable = false, length = 30)
	private ClassAttributionStatus classAttributionStatus;

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

	/*
	 * v06에서 비용 산정 완결 상태가 별도 컬럼으로 분리되고 단가·비용이 전부 NULL 허용으로 바뀌었다.
	 * UNPRICED = 단가가 없어 비용을 계산하지 못한 호출이며, 합계에 0으로 더하지 않고 별도로 드러낸다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "pricing_status", nullable = false, updatable = false, length = 30)
	private PricingStatus pricingStatus;

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

	/**
	 * 실제 비용이 확정됐으면 실제 비용을, 아니면 추정 비용을 반환한다.
	 * 단가 미설정(UNPRICED) 호출은 비용을 알 수 없으므로 <b>0이 아니라 null</b>을 돌려준다 —
	 * 0으로 더하면 청구액이 실제보다 작아 보인다(목업 SA-02·SA-03의 `단가 미설정` 원칙).
	 */
	public BigDecimal resolveCost() {
		if (actualCost != null) {
			return actualCost;
		}
		return pricingStatus == PricingStatus.UNPRICED ? null : estimatedCost;
	}

	/** 단가가 없어 비용 합계에서 제외해야 하는 호출인지. 화면의 `단가 미설정` 표시 근거. */
	public boolean isPricingMissing() {
		return pricingStatus == PricingStatus.UNPRICED;
	}

	/** AI가 무슨 기능을 수행했는지. v06에서 GRADING→ANSWER_GRADING, SESSION_DIALOG→QUESTION_GENERATION으로 바뀌고 CODE_ANALYSIS가 추가됐다. */
	public enum FeatureCode {
		CODE_ANALYSIS, CURRICULUM_ANALYSIS, QUESTION_GENERATION, ANSWER_GRADING, SUMMARY_DRAFT
	}

	/** 호출의 처리 대상 업무 엔터티 유형. */
	public enum ContextType {
		CODE_SNAPSHOT, CURRICULUM_VERSION, ASSESSMENT_SESSION, ASSESSMENT_PROBLEM,
		STAGE_ANSWER_ATTEMPT, REPORT_SNAPSHOT, INTERVENTION
	}

	/** 이번 실행을 직접 시작한 방식. */
	public enum TriggerType {
		USER, SCHEDULED, EVENT, RETRY, BATCH
	}

	/** 비용이 기수·프로젝트 범위에 얼마나 귀속됐는지. */
	public enum AttributionStatus {
		ALLOCATED, PARTIALLY_ALLOCATED, UNALLOCATED
	}

	/** 반 단위 비용 귀속 상태. 기수·프로젝트 귀속과 별개로 관리한다. */
	public enum ClassAttributionStatus {
		ALLOCATED, NOT_APPLICABLE, UNALLOCATED
	}

	/** 호출 비용 산정의 완결 상태. */
	public enum PricingStatus {
		PRICED, UNPRICED, ACTUAL_COST_ONLY
	}

	public enum Status {
		SUCCEEDED, FAILED, PARTIAL
	}
}
