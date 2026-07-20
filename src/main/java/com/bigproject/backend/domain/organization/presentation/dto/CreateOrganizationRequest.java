package com.bigproject.backend.domain.organization.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrganizationRequest(
		@NotBlank String name,
		@Min(30) @Max(3650) int dataRetentionDays
) {
}
