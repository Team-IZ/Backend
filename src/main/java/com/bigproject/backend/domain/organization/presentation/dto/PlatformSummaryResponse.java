package com.bigproject.backend.domain.organization.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 플랫폼 전체 집계. 목업 SA-01 기관 목록 상단의 지표 카드 4개에 대응한다.
 *
 * <pre>
 * 총 기관 14        활성 12 · 정지 2
 * 총 교육생 1,284   활성 세션 37
 * 이번 달 AI 비용 $2,140   예산 $3,300 대비 64% · 전월 +18%
 * 저장량 82 GB      전월 대비 +6% · 기관 평균 5.9GB
 * </pre>
 */
@Schema(description = "플랫폼 전체 집계 (SA-01 상단 지표 카드)")
public record PlatformSummaryResponse(

		@Schema(description = "집계 기준 월(yyyy-MM)")
		YearMonth period,

		Organizations organizations,

		@Schema(description = "플랫폼 전체 활성 교육생 수")
		int traineeCount,

		@Schema(description = "진행 중 세션 수. ⚠ session 계열 테이블이 아직 없어 항상 0입니다.")
		int activeSessionCount,

		AiCost aiCost,
		Storage storage
) {

	@Schema(description = "기관 수 (삭제된 기관 제외)")
	public record Organizations(int total, int active, int suspended) {
	}

	@Schema(description = "AI 비용 집계")
	public record AiCost(
			BigDecimal totalCost,

			@Schema(description = "전 기관 월 예산 합계")
			BigDecimal totalMonthlyBudget,

			@Schema(description = "예산 소진율(0~1). 예산 합계가 0이면 null", nullable = true)
			BigDecimal budgetUsageRate,

			@Schema(description = "전월 대비 증감률. 전월 값이 0이거나 없으면 null", example = "0.18", nullable = true)
			BigDecimal changeRateVsPrevMonth,

			String currencyCode
	) {
	}

	@Schema(description = "저장량 집계")
	public record Storage(
			long totalBytes,

			@Schema(description = "전월 대비 증감률. 전월 값이 0이거나 없으면 null", example = "0.06", nullable = true)
			BigDecimal changeRateVsPrevMonth,

			@Schema(description = "기관 1곳당 평균 저장 바이트")
			long averageBytesPerOrganization
	) {
	}
}
