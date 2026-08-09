package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateOrganizationRequest(

		@Schema(description = "새 기관명. null·빈 값이면 이름을 바꾸지 않는다.", nullable = true)
		String name,

		@Schema(description = "기관 운영 상태. 이름만 바꿀 때도 현재 상태를 그대로 실어 보내야 한다.")
		@NotNull
		OrganizationStatus status
) {
}
