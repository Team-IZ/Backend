package com.bigproject.backend.domain.organization.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OrganizationStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrganizationResponse(
		Long organizationId,
		String name,
		OrganizationStatus status,
		int cohortCount,
		int managerCount,
		int traineeCount,
		BigDecimal currentMonthAiCost,
		int dataRetentionDays,
		Instant createdAt,
		Instant deletedAt
) {
}
