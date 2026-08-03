package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 모델 단가 수정 요청. 목업 SA-03 ① `단가 · 모델별 입력·출력 토큰 단가`.
 *
 * <p><b>단가를 0으로 넣어 "미설정"을 표현하지 마세요.</b> 0은 "무료"라는 뜻이고 미설정과 다릅니다.
 * 미설정으로 되돌리려면 입력·출력 단가를 모두 {@code null}로 보내세요 — 그러면 사용량 집계에서
 * 이 모델 호출이 `단가 미설정`으로 표시되고 비용 합계에서 제외됩니다.
 *
 * <p>단가는 {@code pricePerMillionTokens}(100만 토큰당)로 받습니다. DB는 기준 토큰 수
 * ({@code price_unit_token_count})당 단가를 저장하므로 서버가 환산해 저장합니다.
 */
@Schema(description = "모델 단가 수정 요청")
public record UpdateModelPricingRequest(

		@Schema(description = """
				100만 토큰당 입력 단가. null이면 단가 미설정으로 되돌린다.
				0은 '무료'를 의미하므로 미설정 용도로 쓰지 말 것.""",
				example = "5.000000")
		@DecimalMin("0.000000")
		BigDecimal inputPricePerMillionTokens,

		@Schema(description = "100만 토큰당 출력 단가. null이면 단가 미설정.", example = "25.000000")
		@DecimalMin("0.000000")
		BigDecimal outputPricePerMillionTokens,

		@Schema(description = "100만 토큰당 캐시 입력 단가. 선택.", example = "0.500000")
		@DecimalMin("0.000000")
		BigDecimal cachedInputPricePerMillionTokens,

		@Schema(description = """
				단가의 기준 토큰 수. 생략하면 1,000,000을 사용한다.
				공급자가 다른 기준으로 고지하는 경우에만 바꾼다.""",
				example = "1000000")
		@Positive
		Integer priceUnitTokenCount
) {
	@AssertTrue(message = "입력 단가와 출력 단가는 함께 설정하거나 함께 비워야 합니다.")
	public boolean isPricePairConsistent() {
		return (inputPricePerMillionTokens == null) == (outputPricePerMillionTokens == null);
	}

	@AssertTrue(message = "입력·출력 단가가 없으면 캐시 입력 단가도 설정할 수 없습니다.")
	public boolean isCachedPriceConsistent() {
		return cachedInputPricePerMillionTokens == null || inputPricePerMillionTokens != null;
	}
}
