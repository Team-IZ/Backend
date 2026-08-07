package com.bigproject.backend.domain.organization.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 기관명 사용 가능 여부. 목업 SA-01 생성 모달의 "입력 중 실시간 중복 확인"(✓ 사용 가능한 이름 / ✗ 경고)용.
 * 최종 방어는 여전히 생성 API의 409이며, 이 응답은 입력 중 힌트일 뿐이다.
 */
@Schema(description = "기관명 중복 확인 결과")
public record OrganizationNameAvailabilityResponse(

		@Schema(description = "확인한 기관명(원본)")
		String name,

		@Schema(description = "정규화된 기관명(트림 + 소문자). 중복 판정 기준값")
		String normalizedName,

		@Schema(description = "사용 가능 여부")
		boolean available,

		@Schema(description = "사용 불가 사유. 사용 가능하면 null", example = "이미 사용 중인 기관명입니다.", nullable = true)
		String reason
) {

	public static OrganizationNameAvailabilityResponse available(String name, String normalizedName) {
		return new OrganizationNameAvailabilityResponse(name, normalizedName, true, null);
	}

	public static OrganizationNameAvailabilityResponse taken(String name, String normalizedName) {
		return new OrganizationNameAvailabilityResponse(name, normalizedName, false, "이미 사용 중인 기관명입니다.");
	}
}
