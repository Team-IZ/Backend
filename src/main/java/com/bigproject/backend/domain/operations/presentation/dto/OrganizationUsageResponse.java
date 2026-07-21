package com.bigproject.backend.domain.operations.presentation.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public record OrganizationUsageResponse(
		UUID organizationId,
		YearMonth period,
		StorageUsage storage,
		ActivityUsage activity,
		AiCostUsage aiCost
) {
	public record StorageUsage(
			long totalBytes,
			long codeSubmissionBytes,
			long sessionLogBytes,
			long gradingEvidenceBytes,
			long reportBytes
	) {
	}

	public record ActivityUsage(
			int activeTrainees,
			long completedSessions,
			long gradingRounds,
			long generatedReports
	) {
	}

	public record AiCostUsage(
			BigDecimal totalCost,
			BigDecimal monthlyBudget,
			boolean budgetExceeded,
			List<ModelUsage> models
	) {
	}

	public record ModelUsage(
			String usageType,
			String model,
			long calls,
			long inputTokens,
			long outputTokens,
			BigDecimal unitPricePerMillionTokens,
			BigDecimal cost
	) {
	}
}
