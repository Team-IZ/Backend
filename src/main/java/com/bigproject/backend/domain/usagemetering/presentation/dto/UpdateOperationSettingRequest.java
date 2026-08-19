package com.bigproject.backend.domain.usagemetering.presentation.dto;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.organization.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.global.json.PatchField;
import com.bigproject.backend.global.validation.AllowedRetentionDays;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.stream.Stream;

/**
 * 기관 운영 설정 <b>부분 수정</b> 요청. 목업 SA-02 ④ 설정 탭의 입력 항목과 1:1로 대응한다.
 *
 * <p>설정 탭이 항목별 모달이라 한 번에 바뀌는 값은 2~4개다. 전체 치환이던 시절에는 모달이
 * 담당하지 않는 나머지를 <b>화면이 열릴 때 받아 둔 값으로 다시 실어 보내야</b> 했고, 그 사이 다른
 * 사람이 바꾼 값이 조용히 되돌아갔다. 게다가 {@code organization_policy}가 append-only 이력인데
 * 버전마다 13개가 통째로 다시 쓰여 <b>누가 무엇을 바꿨는지 이력에서 읽을 수 없었다.</b>
 *
 * <p>그래서 전 필드가 선택이다. <b>보내지 않은 필드는 직전 활성 버전의 값을 그대로 승계한다.</b>
 * 다만 아무것도 안 보내는 요청은 버전만 낭비하므로 빈 본문은 400이다.
 *
 * <p>{@code monthlyTokenLimit}·{@code storageLimitBytes}만 {@link PatchField}로 받는다 —
 * 두 필드는 {@code null}이 "값 없음"이 아니라 <b>무제한</b>이라는 값이라, 안 보낸 것과 구분해야
 * 상한을 다시 풀 수 있다. 나머지 필드는 {@code null}이 의미를 갖지 않으므로 생략과 같게 취급한다.
 *
 * <p>통화({@code currencyCode})는 플랫폼 공통 USD 고정이라 요청 항목이 아니다(DB CHECK로도 강제된다).
 */
@Schema(description = """
		기관 운영 설정 부분 수정 요청.

		**전 필드가 선택이다.** 보내지 않은 필드는 직전 활성 버전의 값을 그대로 승계한다.
		빈 본문 `{}`은 400이다 — 아무것도 바꾸지 않는 요청은 정책 버전만 올린다.

		`monthlyTokenLimit`·`storageLimitBytes`는 **`null`을 보내면 무제한으로 푼다.**
		유지하려면 키를 아예 빼세요.

		`organizationStatus`는 **ACTIVE 또는 SUSPENDED만** 직접 지정할 수 있다 — 그 외 값은 400이다.
		삭제 상태를 되돌리는 것은 이 API가 아니라 `POST /organizations/{organizationId}/restore`다.""")
public record UpdateOperationSettingRequest(

		@Schema(description = "기관 운영 상태. ACTIVE 또는 SUSPENDED만 지정할 수 있다", nullable = true)
		OrganizationStatus organizationStatus,

		@Schema(description = "AI 월 예산 상한. 0은 무제한이 아니라 예산 0을 의미한다.", example = "600.00", nullable = true)
		@DecimalMin("0.00")
		BigDecimal monthlyAiBudget,

		@Schema(description = """
				기관 월 토큰 한도. 넘으면 새 세션이 열리지 않는다.
				**`null`을 보내면 무제한으로 푼다.** 유지하려면 키를 빼세요.""",
				implementation = Long.class, example = "200000000", nullable = true)
		PatchField<Long> monthlyTokenLimit,

		@Schema(description = """
				저장량 상한(바이트). **`null`을 보내면 무제한으로 푼다.** 유지하려면 키를 빼세요.""",
				implementation = Long.class, example = "107374182400", nullable = true)
		PatchField<Long> storageLimitBytes,

		@Schema(description = "데이터 보존기간(일)", allowableValues = {"90", "180", "365"}, example = "180", nullable = true)
		@AllowedRetentionDays
		Integer dataRetentionDays,

		@Schema(description = "신규 기수 공개 범위 기본값", nullable = true)
		DisclosureScope defaultDisclosureScope,

		// 코드 세션 기능의 모델 티어. v07에서 질문 생성·요약 티어가 이 값 하나로 통합됐다
		// (questionGenerationTierCode·summaryTierCode 대체).
		@Schema(description = "코드 세션 모델 티어", nullable = true)
		AiTier codeSessionTierCode,

		@Schema(description = "신규 매니저 초대·재발송 허용 여부", nullable = true)
		Boolean allowManagerInvite,

		@Schema(description = "신규 데이터 export 생성 허용 여부", nullable = true)
		Boolean allowDataExport,

		@Schema(description = "ZIP 코드 제출 허용 여부. 끄면 GitHub 연동만 남는다.", nullable = true)
		Boolean allowZipSubmission,

		@Schema(description = """
				GitHub 조직 연동 허용 여부. 이 값은 정책(허용 여부)이며 실제 연결/해제는 별도 연동 흐름이다.""",
				nullable = true)
		Boolean allowGithubIntegration
) {

	@JsonIgnore
	@AssertTrue(message = "기관 운영 상태는 활성 또는 정지만 직접 설정할 수 있습니다.")
	public boolean isMutableOrganizationStatus() {
		return organizationStatus == null
				|| organizationStatus == OrganizationStatus.ACTIVE
				|| organizationStatus == OrganizationStatus.SUSPENDED;
	}

	/**
	 * 빈 본문 차단. 부분 수정이라 필드마다 {@code @NotNull}을 걸 수 없는데, 그 상태로 {@code {}}를
	 * 받아 주면 <b>바뀐 것 없이 정책 버전만 하나 올라간다.</b> 이력을 읽는 쪽에서는 원인 없는 버전이
	 * 섞이는 셈이라 여기서 막는다.
	 */
	@JsonIgnore
	@AssertTrue(message = "변경할 항목을 하나 이상 보내야 합니다.")
	public boolean isNotEmpty() {
		return Stream.of(organizationStatus, monthlyAiBudget, monthlyTokenLimit, storageLimitBytes,
						dataRetentionDays, defaultDisclosureScope, codeSessionTierCode,
						allowManagerInvite, allowDataExport, allowZipSubmission, allowGithubIntegration)
				.anyMatch(field -> field != null);
	}

	/** 상한은 보낼 때만 검사한다. 래퍼에 담겨 있어 {@code @Positive}가 닿지 않는다. */
	@JsonIgnore
	@AssertTrue(message = "월 토큰 한도는 0보다 커야 합니다. 무제한으로 풀려면 null을 보내세요.")
	public boolean isPositiveMonthlyTokenLimit() {
		return monthlyTokenLimit == null || monthlyTokenLimit.value() == null || monthlyTokenLimit.value() > 0;
	}

	@JsonIgnore
	@AssertTrue(message = "저장량 상한은 0 이상이어야 합니다. 무제한으로 풀려면 null을 보내세요.")
	public boolean isPositiveOrZeroStorageLimit() {
		return storageLimitBytes == null || storageLimitBytes.value() == null || storageLimitBytes.value() >= 0;
	}
}
