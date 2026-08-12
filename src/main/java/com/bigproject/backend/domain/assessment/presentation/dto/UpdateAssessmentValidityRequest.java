package com.bigproject.backend.domain.assessment.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateAssessmentValidityRequest(
		@NotNull Decision decision,
		@NotNull DecisionReason reasonCode,
		@Size(max = 2000) String note,
		@PositiveOrZero int rowVersion
) {
	public enum Decision { CONFIRM_INVALID, RESTORE_VALID }
	public enum DecisionReason {
		REVIEWED_NO_VIOLATION,
		REVIEWED_VIOLATION_CONFIRMED,
		REVIEWED_INSUFFICIENT_EVIDENCE
	}
}
