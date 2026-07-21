package com.bigproject.backend.domain.organization.presentation.dto;

import java.time.Instant;
import java.util.UUID;

public record DeleteOrganizationResponse(
		UUID organizationId,
		Instant deletedAt,
		Instant purgeAvailableAt
) {
}
