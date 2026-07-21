package com.bigproject.backend.domain.organization.presentation.dto;

import java.time.Instant;

public record DeleteOrganizationResponse(
		Long organizationId,
		Instant deletedAt,
		Instant purgeAvailableAt
) {
}
