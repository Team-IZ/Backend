package com.bigproject.backend.domain.usagemetering.presentation.dto;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * 기관 운영 설정 변경 요청. 목업 SA-02 ④ 설정 탭의 입력 항목과 1:1로 대응한다.
 *
 * <p>부분 수정이 아니라 전체 치환이다 — organization_policy가 append-only 버전 이력이라
 * 기존 활성 버전을 닫고 새 버전을 발급하기 때문에, 새 버전에 들어갈 값을 모두 받아야 한다.
 *
 * <p>v06에서 컬럼이 생겨 <b>전 항목이 실제로 저장된다</b>. 이전에 "저장되지 않음"으로 표시했던
 * 월 토큰 한도·GitHub 연동·ZIP 업로드·빅프 기여도 분석이 모두 실 저장 대상이 됐고,
 * 저장량 상한·매니저 초대·데이터 내보내기·티어 2종이 새로 추가됐다.
 *
 * <p>통화(currencyCode)는 플랫폼 공통 USD 고정이라 요청 항목이 아니다(DB CHECK로도 강제된다).
 */
@Schema(description = "기관 운영 설정 변경 요청 (전체 치환)")
public record UpdateOperationSettingRequest(

		@Schema(description = "기관 상태. ACTIVE 또는 SUSPENDED만 직접 지정 가능", example = "ACTIVE")
		@NotNull
		OrganizationStatus organizationStatus,

		@Schema(description = "AI 월 예산 상한. 0은 무제한이 아니라 예산 0을 의미한다.", example = "600.00")
		@NotNull @DecimalMin("0.00")
		BigDecimal monthlyAiBudget,

		@Schema(description = """
				기관 월 토큰 한도. 넘으면 새 세션이 열리지 않는다. null이면 무제한이다.""",
				example = "200000000")
		@Positive
		Long monthlyTokenLimit,

		@Schema(description = "저장량 상한(바이트). null이면 무제한이다.", example = "107374182400")
		@PositiveOrZero
		Long storageLimitBytes,

		@Schema(description = "데이터 보존기간(일)", example = "180")
		@Min(30) @Max(3650)
		int dataRetentionDays,

		@Schema(description = "신규 기수 공개 범위 기본값", example = "SUMMARY")
		@NotNull
		DisclosureScope defaultDisclosureScope,

		@Schema(description = "질문 생성 기능의 모델 티어", example = "BALANCED")
		@NotNull
		AiTier questionGenerationTierCode,

		@Schema(description = "요약 초안 기능의 모델 티어", example = "BALANCED")
		@NotNull
		AiTier summaryTierCode,

		@Schema(description = "신규 매니저 초대·재발송 허용 여부")
		@NotNull
		Boolean allowManagerInvite,

		@Schema(description = "신규 데이터 export 생성 허용 여부")
		@NotNull
		Boolean allowDataExport,

		@Schema(description = "ZIP 코드 제출 허용 여부. 끄면 GitHub 연동만 남는다.")
		@NotNull
		Boolean allowZipSubmission,

		@Schema(description = """
				GitHub 조직 연동 허용 여부. 이 값은 정책(허용 여부)이며 실제 연결/해제는 별도 연동 흐름이다.""")
		@NotNull
		Boolean allowGithubIntegration,

		@Schema(description = "빅프로젝트 기여도 분석 실행 허용 여부")
		@NotNull
		Boolean enableBigProjectContributionAnalysis
) {
	@AssertTrue(message = "기관 운영 상태는 활성 또는 정지만 직접 설정할 수 있습니다.")
	public boolean isMutableOrganizationStatus() {
		return organizationStatus == null
				|| organizationStatus == OrganizationStatus.ACTIVE
				|| organizationStatus == OrganizationStatus.SUSPENDED;
	}
}
