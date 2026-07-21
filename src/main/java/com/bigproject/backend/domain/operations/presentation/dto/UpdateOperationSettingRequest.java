package com.bigproject.backend.domain.operations.presentation.dto;

import com.bigproject.backend.domain.operations.domain.DisclosureScope;
import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateOperationSettingRequest(
		@NotNull OrganizationStatus organizationStatus,
		@NotNull @DecimalMin("0.00") BigDecimal monthlyAiBudget,
		@Min(30) @Max(3650) int dataRetentionDays,
		@NotNull DisclosureScope defaultDisclosureScope
) {
	@AssertTrue(message = "기관 운영 상태는 활성 또는 정지만 직접 설정할 수 있습니다.")
	public boolean isMutableOrganizationStatus() {
		return organizationStatus == null
				|| organizationStatus == OrganizationStatus.ACTIVE
				|| organizationStatus == OrganizationStatus.SUSPENDED;
	}
}
