package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrganizationRequest(
		String name,
		@NotNull OrganizationStatus status
) {
}
