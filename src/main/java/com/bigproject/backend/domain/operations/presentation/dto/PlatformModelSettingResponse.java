package com.bigproject.backend.domain.operations.presentation.dto;

import com.bigproject.backend.domain.operations.domain.AiTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 플랫폼 모델·단가 설정. 목업 SA-03 ① `모델 · 단가` 탭에 대응한다.
 *
 * <pre>
 * 채점        고정 · claude-x         ← 전 기관 공통. 바꾸면 재캘리브레이션
 * 질문 생성   정확도 우선 / 균형 / 비용 우선   3티어 ↔ 모델 매핑
 * 단가        모델별 입력·출력 토큰 단가
 * </pre>
 */
@Schema(description = "플랫폼 모델·단가 설정 (SA-03 ① 모델·단가 탭)")
public record PlatformModelSettingResponse(

		@Schema(description = "채점 모델 정책. 전 기관 공통이며 기관이 바꿀 수 없다.")
		GradingPolicy gradingPolicy,

		@Schema(description = """
				기능별 티어 ↔ 모델 매핑. 기관은 티어 이름만 고르고 실제 모델은 이 매핑이 정한다.
				모델이 단종되면 이 매핑만 바꾸면 되므로 기관 쪽에서 할 일이 없다.""")
		List<TierMapping> tierMappings,

		@Schema(description = "모델별 단가 목록. 단가 미설정 모델도 포함되며 pricingMissing=true로 표시된다.")
		List<ModelPricing> modelPricings
) {

	@Schema(description = "채점 모델 정책 (플랫폼 고정)")
	public record GradingPolicy(
			UUID gradingPolicyId,
			int policyVersion,
			UUID modelId,

			@Schema(description = "모델 표시명", example = "claude-opus-5")
			String modelDisplayName,

			@Schema(description = "모델 코드", example = "CLAUDE_OPUS_5")
			String modelCode,

			Instant effectiveFrom,

			@Schema(description = "변경 사유")
			String changeReason,

			@Schema(description = """
					현재 결과 비교의 기준이 되는 캘리브레이션 버전. 재캘리브레이션이 진행 중이면
					activeCalibration은 이전 버전이고 runningCalibration에 진행 중 버전이 담긴다.""")
			CalibrationSummary activeCalibration,

			@Schema(description = "진행 중인 재캘리브레이션. 없으면 null")
			CalibrationSummary runningCalibration
	) {
	}

	@Schema(description = "기능 × 티어 → 모델 매핑 한 줄")
	public record TierMapping(
			UUID tierPolicyId,

			@Schema(description = "적용 기능. QUESTION_GENERATION(질문 생성) / SUMMARY_DRAFT(요약)",
					example = "QUESTION_GENERATION")
			String featureCode,

			@Schema(description = "티어. ACCURACY_FIRST(정확도 우선) / BALANCED(균형) / COST_FIRST(비용 우선)")
			AiTier tierCode,

			UUID modelId,
			String modelDisplayName,
			String modelCode,
			int policyVersion,
			Instant effectiveFrom
	) {
	}

	@Schema(description = "모델별 단가")
	public record ModelPricing(
			UUID modelId,
			String modelCode,
			String modelDisplayName,
			String provider,

			@Schema(description = "모델 사용 가능 상태. ACTIVE / INACTIVE")
			String status,

			@Schema(description = "100만 토큰당 입력 단가. 미설정이면 null", example = "5.000000")
			BigDecimal inputPricePerMillionTokens,

			@Schema(description = "100만 토큰당 출력 단가. 미설정이면 null", example = "25.000000")
			BigDecimal outputPricePerMillionTokens,

			@Schema(description = "100만 토큰당 캐시 입력 단가. 미설정이면 null")
			BigDecimal cachedInputPricePerMillionTokens,

			@Schema(description = "단가 통화. 플랫폼 공통 USD", example = "USD")
			String currencyCode,

			@Schema(description = """
					단가 미설정 여부. true면 이 모델의 호출 비용을 계산할 수 없고,
					사용량 집계에서도 0으로 더하지 않고 제외된다
					(목업: "0으로 합산하면 청구액이 실제보다 작아 보인다").""")
			boolean pricingMissing,

			Instant priceEffectiveFrom,
			Instant priceUpdatedAt
	) {
	}

	@Schema(description = "캘리브레이션 버전 요약")
	public record CalibrationSummary(
			UUID calibrationVersionId,

			@Schema(description = "화면·비교에 쓰는 불변 버전 코드", example = "CAL_2026_08_V1")
			String versionCode,

			@Schema(description = "PENDING / RUNNING / ACTIVE / FAILED / SUPERSEDED")
			String status,

			Instant startedAt,
			Instant completedAt,

			@Schema(description = "기관별 진행 현황. 목업 확인 모달의 `전 기관 재캘리브레이션` 진행률에 쓴다.")
			CalibrationProgress progress
	) {
	}

	@Schema(description = "기관별 재캘리브레이션 진행 현황")
	public record CalibrationProgress(
			int totalOrganizations,
			int pending,
			int running,
			int succeeded,
			int failed,

			@Schema(description = "완료율(0~1). 대상 기관이 0이면 null", example = "0.75")
			BigDecimal completionRate
	) {
	}
}
