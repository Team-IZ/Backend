package com.bigproject.backend.domain.operations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
 * organization_usage_snapshot 테이블 매핑 엔티티. v06 신규.
 *
 * <p>기관별 기간 사용 규모·AI 비용을 <b>미리 집계해 둔 스냅샷</b>이다. 이전에는 요청마다 ai_usage를 전부 훑어
 * 집계했지만, v06부터는 이 테이블을 우선 조회한다(수집 배치 책임).
 *
 * <p>이 테이블이 들어오면서 <b>집계 실패 표현의 위치가 바뀌었다.</b> storage_usage_snapshot에서
 * {@code aggregation_status}가 사라진 대신(실패 측정은 아예 저장하지 않음), 기간 집계의 성공·실패는 여기서 판정한다.
 * 목업 SA-02 case 6: "집계 실패를 0으로 보여주지 않는다 — 안 쓴 것과 못 읽은 것은 다르다".
 *
 * <p>{@code cost_completeness_status}와 {@code unpriced_*} 컬럼은 "일부 호출에 단가가 없어 비용이 과소 집계됨"을
 * 드러내기 위한 값이다. 전량 실패가 아니라 <b>부분 결손</b>을 표현한다.
 */
@Getter
@Entity
@Table(name = "organization_usage_snapshot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationUsageSnapshot {

	@Id
	@UuidGenerator
	@Column(name = "usage_snapshot_id", updatable = false, nullable = false)
	private UUID usageSnapshotId;

	@Column(name = "org_id", nullable = false, updatable = false)
	private UUID orgId;

	@Enumerated(EnumType.STRING)
	@Column(name = "period_type", nullable = false, updatable = false, length = 20)
	private PeriodType periodType;

	@Column(name = "period_start_at", nullable = false, updatable = false)
	private Instant periodStartAt;

	@Column(name = "period_end_at", nullable = false, updatable = false)
	private Instant periodEndAt;

	/** 원천 반영 기준 시각. 같은 기간에 여러 번 집계될 수 있어 성공 스냅샷의 유일성 기준이 된다. */
	@Column(name = "as_of_at", nullable = false, updatable = false)
	private Instant asOfAt;

	@Column(name = "active_trainee_count", updatable = false)
	private Integer activeTraineeCount;

	@Column(name = "completed_session_count", updatable = false)
	private Integer completedSessionCount;

	@Column(name = "grading_execution_count", updatable = false)
	private Integer gradingExecutionCount;

	@Column(name = "published_report_count", updatable = false)
	private Integer publishedReportCount;

	@Column(name = "ai_call_count", updatable = false)
	private Long aiCallCount;

	@Column(name = "input_token_count", updatable = false)
	private Long inputTokenCount;

	@Column(name = "output_token_count", updatable = false)
	private Long outputTokenCount;

	@Column(name = "cached_token_count", updatable = false)
	private Long cachedTokenCount;

	/** 단가 미설정 상태로 기록된 호출 수. 0보다 크면 비용 합계가 실제보다 작다는 뜻이다. */
	@Column(name = "unpriced_call_count", updatable = false)
	private Long unpricedCallCount;

	@Column(name = "unpriced_input_token_count", updatable = false)
	private Long unpricedInputTokenCount;

	@Column(name = "unpriced_output_token_count", updatable = false)
	private Long unpricedOutputTokenCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "cost_completeness_status", updatable = false, length = 30)
	private CostCompletenessStatus costCompletenessStatus;

	@Column(name = "estimated_cost", updatable = false, precision = 18, scale = 6)
	private BigDecimal estimatedCost;

	@Column(name = "actual_cost", updatable = false, precision = 18, scale = 6)
	private BigDecimal actualCost;

	/** 호출별 실제 비용 우선, 없으면 추정 비용으로 계산한 표시 비용. 성공 스냅샷이면 필수다. */
	@Column(name = "effective_cost", updatable = false, precision = 18, scale = 6)
	private BigDecimal effectiveCost;

	@Column(name = "currency_code", updatable = false, length = 3)
	private String currencyCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregation_status", nullable = false, updatable = false, length = 20)
	private AggregationStatus aggregationStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_stage", updatable = false, length = 100)
	private FailureStage failureStage;

	@Column(name = "failure_code", updatable = false, length = 100)
	private String failureCode;

	@Column(name = "failure_reason", updatable = false)
	private String failureReason;

	@Column(name = "failed_at", updatable = false)
	private Instant failedAt;

	@Column(name = "is_retryable", updatable = false)
	private Boolean isRetryable;

	@Column(name = "source_watermark", updatable = false)
	private String sourceWatermark;

	@Column(name = "calculation_version", nullable = false, updatable = false)
	private Integer calculationVersion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 집계에 성공한 스냅샷인지. 실패 스냅샷의 값은 신뢰할 수 없어 화면에 0으로 그리면 안 된다. */
	public boolean isSucceeded() {
		return aggregationStatus == AggregationStatus.SUCCEEDED;
	}

	/** 단가 미설정 호출이 섞여 비용 합계가 실제보다 작은지. */
	public boolean hasUnpricedCalls() {
		return costCompletenessStatus == CostCompletenessStatus.PARTIAL_UNPRICED
				|| (unpricedCallCount != null && unpricedCallCount > 0);
	}

	public enum PeriodType {
		DAILY, MONTHLY, CUSTOM
	}

	public enum CostCompletenessStatus {
		COMPLETE, PARTIAL_UNPRICED
	}

	public enum AggregationStatus {
		SUCCEEDED, FAILED
	}

	public enum FailureStage {
		STORAGE_MEASUREMENT, ACTIVITY_AGGREGATION, AI_COST_AGGREGATION, SNAPSHOT_COMPOSITION, CURRENCY_VALIDATION
	}
}
