package com.bigproject.backend.domain.notification.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SendReminderRequest(
		@NotNull UUID assessmentRoundId,
		UUID teamId,
		UUID traineeId,
		@NotNull ReminderReason reasonCode
) {
	public enum ReminderReason {
		TEAM_SUBMISSION_MISSING,
		TEAM_ANALYSIS_FAILED,
		INDIVIDUAL_ASSESSMENT_NOT_STARTED
	}
}
