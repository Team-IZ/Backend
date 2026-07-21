package com.bigproject.backend.domain.member.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateManagerAssignmentsRequest(
		@NotNull List<@Valid ManagerAssignmentRequest> assignments,
		String reason
) {
}
