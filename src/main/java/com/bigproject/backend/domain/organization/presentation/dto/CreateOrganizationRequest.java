package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.global.validation.AllowedRetentionDays;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 기관 생성 요청. 목업 SA-01 `기관 생성 (테넌트 프로비저닝)` 모달의 입력 3개와 1:1로 대응한다. */
public record CreateOrganizationRequest(

		@Schema(description = "기관명", example = "코드베이스 아카데미")
		@NotBlank
		String name,

		@Schema(description = """
				초대 허용 이메일 도메인. 이 도메인 밖 주소로는 계정 초대를 보낼 수 없다.
				비우면 도메인 제한을 적용하지 않는다.""",
				example = "codebase.ac.kr", nullable = true)
		@Pattern(
				regexp = "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$",
				message = "도메인 형식이 올바르지 않습니다."
		)
		String emailDomain,

		@Schema(description = """
				URL·외부 연동에 쓰는 짧은 기관 식별값(`/admin/orgs/greencompany`). 비우면 저장하지 않는다.
				소문자·숫자와 하이픈만 허용하며 전체에서 유일해야 한다.""",
				example = "codebase-academy", nullable = true)
		@Pattern(
				regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "슬러그는 소문자·숫자와 하이픈만 사용할 수 있습니다."
		)
		@Size(max = 64)
		String slug,

		@Schema(description = """
				화면 표시용 기관 코드(`ORG_GRN_4F21`). 권한·테넌트 판정에는 사용하지 않는다.""",
				example = "ORG_CODEBASE", nullable = true)
		@Pattern(
				regexp = "^[A-Z][A-Z0-9_]*$",
				message = "표시 코드는 영문 대문자로 시작하고 대문자·숫자·밑줄만 사용할 수 있습니다."
		)
		@Size(max = 32)
		String displayCode,

		@Schema(
				description = "데이터 보존기간(일). 종료 기수의 코드·문답·채점 근거 보관 기간",
				allowableValues = {"90", "180", "365"},
				example = "180",
				requiredMode = Schema.RequiredMode.REQUIRED
		)
		@AllowedRetentionDays
		int dataRetentionDays
) {
}
