package com.bigproject.backend.domain.platformgovernance.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * ai_model 테이블 매핑 엔티티. AI 실행에 사용하는 모델(제공자·모델 코드·컨텍스트 한도 등)의 중앙 마스터.
 * 이 작업 범위(사용량 조회)에서는 {@link AiUsage}가 참조하는 READ 전용 정보로만 사용되며,
 * 모델 등록/변경 API 자체는 organization/operations 도메인 범위 밖이다.
 */
@Getter
@Entity
@Table(name = "ai_model")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiModel {

	@Id
	@UuidGenerator
	@Column(name = "model_id", updatable = false, nullable = false)
	private UUID modelId;

	@Column(name = "model_code", nullable = false, updatable = false, length = 100)
	private String modelCode;

	@Column(name = "provider", nullable = false, updatable = false, length = 100)
	private String provider;

	@Column(name = "provider_model_code", nullable = false, updatable = false, length = 100)
	private String providerModelCode;

	@Column(name = "display_name", nullable = false, length = 200)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 100)
	private Status status;

	// 모델의 기능/제약 정보(JSON). 별도 파싱 없이 원문 JSON 문자열로만 보관한다. v06에서 NULL 허용으로 완화됐다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "capability_payload", columnDefinition = "jsonb")
	private String capabilityPayload;

	@Column(name = "context_window", nullable = false)
	private Integer contextWindow;

	@Column(name = "max_output_tokens", nullable = false)
	private Long maxOutputTokens;

	/*
	 * v06에서 모델의 유효기간(effective_from/effective_to)이 사라지고, 그 자리에 "현재 단가" 스냅샷이 들어왔다.
	 * 단가 이력은 ai_usage가 호출 시점 값을 복사해 보관하므로 여기에는 현재 값만 둔다.
	 * 단가는 미설정(NULL)일 수 있고, 그 경우 사용량 화면에서 `단가 미설정`으로 표시하고 합계에서 제외한다
	 * (목업 SA-03: "0으로 계산하면 청구액이 실제보다 작아 보인다").
	 */
	@Column(name = "input_unit_price", precision = 18, scale = 6)
	private BigDecimal inputUnitPrice;

	@Column(name = "output_unit_price", precision = 18, scale = 6)
	private BigDecimal outputUnitPrice;

	@Column(name = "cached_input_unit_price", precision = 18, scale = 6)
	private BigDecimal cachedInputUnitPrice;

	// DB CHECK: currency_code IS NULL OR currency_code = 'USD' (v06에서 플랫폼 공통 USD 고정)
	@Column(name = "currency_code", length = 3)
	private String currencyCode;

	/** 단가의 기준 토큰 수. DEFAULT 1000000이며, 100만 토큰당 단가로 환산할 때 이 값을 나눗셈 기준으로 쓴다. */
	@Column(name = "price_unit_token_count")
	private Integer priceUnitTokenCount;

	@Column(name = "price_effective_from")
	private Instant priceEffectiveFrom;

	@Column(name = "price_updated_by")
	private UUID priceUpdatedBy;

	@Column(name = "price_updated_at")
	private Instant priceUpdatedAt;

	@Column(name = "data_processing_region", nullable = false)
	private String dataProcessingRegion;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** 단가가 설정돼 있는지. 입력·출력 단가가 모두 있어야 비용을 계산할 수 있다. */
	public boolean hasPricing() {
		return inputUnitPrice != null && outputUnitPrice != null;
	}

	/**
	 * 100만 토큰당 입력 단가. 단가 기준 토큰 수(price_unit_token_count)가 100만이 아닐 수 있어 환산해서 돌려준다.
	 * 단가가 없으면 null(→ 화면의 `단가 미설정`).
	 */
	public BigDecimal inputPricePerMillionTokens() {
		return perMillionTokens(inputUnitPrice);
	}

	/** 100만 토큰당 출력 단가. {@link #inputPricePerMillionTokens()}와 동일한 환산 규칙. */
	public BigDecimal outputPricePerMillionTokens() {
		return perMillionTokens(outputUnitPrice);
	}

	/** 100만 토큰당 캐시 입력 단가. 미설정이면 null. */
	public BigDecimal cachedInputPricePerMillionTokens() {
		return perMillionTokens(cachedInputUnitPrice);
	}

	/**
	 * 현재 단가를 갈아끼운다(SA-03 단가 수정).
	 *
	 * <p>입력·출력 단가가 모두 {@code null}이면 <b>단가 미설정</b>으로 되돌리고 통화·적용시각도 비운다 —
	 * 0으로 남겨 두면 "무료"로 오해되고, 사용량 집계가 비용을 0으로 더해 청구액이 실제보다 작아 보인다.
	 *
	 * <p>단가 이력은 이 테이블에 쌓지 않는다. 호출 시점 단가는 {@code ai_usage}가 복사해 보관하므로
	 * 과거 청구 근거는 그쪽에 남는다.
	 */
	public void applyPricing(
			BigDecimal inputUnitPrice,
			BigDecimal outputUnitPrice,
			BigDecimal cachedInputUnitPrice,
			int priceUnitTokenCount,
			UUID updatedBy
	) {
		this.inputUnitPrice = inputUnitPrice;
		this.outputUnitPrice = outputUnitPrice;
		this.cachedInputUnitPrice = cachedInputUnitPrice;

		if (inputUnitPrice == null && outputUnitPrice == null) {
			this.currencyCode = null;
			this.priceUnitTokenCount = null;
			this.priceEffectiveFrom = null;
			this.priceUpdatedBy = updatedBy;
			this.priceUpdatedAt = null;
			return;
		}

		// DB CHECK: 단가가 설정되면 통화는 USD여야 하고 적용시각·수정시각이 필수다.
		this.currencyCode = PLATFORM_CURRENCY_CODE;
		this.priceUnitTokenCount = priceUnitTokenCount;
		Instant now = Instant.now();
		this.priceEffectiveFrom = now;
		this.priceUpdatedBy = updatedBy;
		this.priceUpdatedAt = now;
	}

	/** 단가 통화. DB CHECK(currency_code IS NULL OR currency_code = 'USD')와 같은 값. */
	public static final String PLATFORM_CURRENCY_CODE = "USD";

	private BigDecimal perMillionTokens(BigDecimal unitPrice) {
		if (unitPrice == null) {
			return null;
		}
		int unitTokens = priceUnitTokenCount == null ? DEFAULT_PRICE_UNIT_TOKEN_COUNT : priceUnitTokenCount;
		if (unitTokens <= 0) {
			return null;
		}
		return unitPrice
				.multiply(BigDecimal.valueOf(DEFAULT_PRICE_UNIT_TOKEN_COUNT))
				.divide(BigDecimal.valueOf(unitTokens), 6, RoundingMode.HALF_UP);
	}

	/** ai_model.price_unit_token_count의 DB DEFAULT와 동일한 값. */
	private static final int DEFAULT_PRICE_UNIT_TOKEN_COUNT = 1_000_000;

	// DB CHECK: status IN ('ACTIVE', 'INACTIVE')
	public enum Status {
		ACTIVE, INACTIVE
	}
}
