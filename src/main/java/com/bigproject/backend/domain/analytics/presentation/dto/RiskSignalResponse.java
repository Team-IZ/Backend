package com.bigproject.backend.domain.analytics.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RiskSignalResponse(UUID cohortId, List<Signal> signals) {
	public record Signal(
			UUID signalId, String reasonCode, UUID assessmentRoundId, UUID classroomId,
			UUID teamId, UUID traineeId, String traineeName, String summary,
			String status, int policyVersion, OffsetDateTime detectedAt) {
	}
}
