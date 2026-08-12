package com.bigproject.backend.domain.assessment.presentation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssessmentValidityResponse(
		UUID attemptId, String validityReviewStatus, String decisionReasonCode,
		String decisionNote, int rowVersion, OffsetDateTime reviewedAt) {
}
