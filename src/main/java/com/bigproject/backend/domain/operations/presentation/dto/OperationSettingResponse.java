package com.bigproject.backend.domain.operations.presentation.dto;

import com.bigproject.backend.domain.operations.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;

import java.math.BigDecimal;

public record OperationSettingResponse(
		Long organizationId,
		OrganizationStatus organizationStatus,
		BigDecimal monthlyAiBudget,
		int dataRetentionDays,
		DisclosureScope defaultDisclosureScope
) {
}
