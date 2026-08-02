package com.bigproject.backend.domain.operations.presentation.dto;

import com.bigproject.backend.domain.operations.domain.AiTier;
import com.bigproject.backend.domain.operations.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 기관 운영 설정. 목업 SA-02 ④ 설정 탭에 대응한다.
 * (기관 상태 / AI 월 예산 상한 / 월 토큰 한도 / 저장량 상한 / 데이터 보존기간 / 신규 기수 공개 범위 기본값 /
 *  질문생성·요약 티어 / 매니저 초대 · 데이터 내보내기 · ZIP 업로드 · GitHub 연동 · 빅프 기여도 분석 토글 /
 *  기관 삭제 — 삭제는 별도 DELETE 엔드포인트)
 *
 * <p>v06에서 organization_policy에 컬럼이 생기면서, 이전에 {@code OperationsSchemaPending}으로 대체하던 값
 * (월 토큰 한도 · GitHub 연동 · ZIP 업로드 · 빅프 기여도 분석)이 <b>실제 저장 값</b>으로 바뀌었다.
 * 더불어 신규 정책 5종(저장량 상한 · 매니저 초대 · 데이터 내보내기 · 질문생성/요약 티어)도 함께 노출한다.
 */
@Schema(description = "기관 운영 설정 (SA-02 ④ 설정 탭)")
public record OperationSettingResponse(
		UUID organizationId,

		@Schema(description = "기관 상태. 정지하면 소속 오퍼레이터·매니저·교육생의 로그인이 막히고 데이터는 보존된다.")
		OrganizationStatus organizationStatus,

		@Schema(description = "AI 월 예산 상한. 넘으면 `예산 초과` 배지와 경고가 붙지만 서비스 중단은 아니다.")
		BigDecimal monthlyAiBudget,

		@Schema(description = "예산 통화 코드. 플랫폼 공통 USD 고정이며 기관별로 바꿀 수 없다.", example = "USD")
		String currencyCode,

		@Schema(description = """
				기관 월 토큰 한도. 넘으면 새 세션이 열리지 않고, 진행 중 세션은 완료된 문제까지 저장하고 종료한다.
				null이면 무제한이다.""",
				example = "200000000")
		Long monthlyTokenLimit,

		@Schema(description = "저장량 상한(바이트). null이면 무제한이다.", example = "107374182400")
		Long storageLimitBytes,

		@Schema(description = "데이터 보존기간(일). 종료 기수의 코드·문답 원문·채점 근거 보관 기간, 경과분 파기")
		int dataRetentionDays,

		@Schema(description = "신규 기수 공개 범위 기본값. 회차별 조정은 매니저가 한다.")
		DisclosureScope defaultDisclosureScope,

		@Schema(description = """
				질문 생성 기능의 모델 티어. 기관은 <b>티어 이름만</b> 고르고 실제 모델은 플랫폼이 정한다(SA-03).""")
		AiTier questionGenerationTierCode,

		@Schema(description = "요약 초안 기능의 모델 티어.")
		AiTier summaryTierCode,

		@Schema(description = "신규 매니저 초대·재발송 허용 여부.")
		Boolean allowManagerInvite,

		@Schema(description = "신규 데이터 export 생성 허용 여부.")
		Boolean allowDataExport,

		@Schema(description = "ZIP 코드 제출 허용 여부. 끄면 GitHub 연동만 남는다.")
		Boolean allowZipSubmission,

		@Schema(description = """
				GitHub 조직 연동 허용 여부. 이 값은 <b>정책(허용 여부)</b>이고,
				실제 연결 상태는 organization_github_integration이 따로 관리한다.""")
		Boolean allowGithubIntegration,

		@Schema(description = "빅프로젝트 기여도 분석(커밋 기준) 실행 허용 여부. 미니프로젝트는 대상이 아니다.")
		Boolean enableBigProjectContributionAnalysis,

		@Schema(description = "이 설정이 속한 정책 버전. organization_policy는 append-only 이력이라 변경할 때마다 올라간다.")
		int policyVersion
) {
}
