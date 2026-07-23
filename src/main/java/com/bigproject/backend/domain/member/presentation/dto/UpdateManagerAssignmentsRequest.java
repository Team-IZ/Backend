package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateManagerAssignmentsRequest(
		@Schema(description = "교체 적용할 기수·반 담당 범위 목록")
		@NotNull List<@Valid ManagerAssignmentRequest> assignments,
		@Schema(description = "배정 변경 사유", example = "7기 운영 담당 변경")
		String reason
) {
}
