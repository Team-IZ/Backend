package com.bigproject.backend.domain.assessment.domain;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentValidityRepository {
	Optional<Attempt> lockAttempt(UUID attemptId, UUID managerId);

	int updateDecision(UUID attemptId, int rowVersion, String status, String reasonCode, String note, UUID managerId);

	void activateInvalidReason(Attempt attempt);

	void resolveInvalidReason(Attempt attempt);

	void insertAudit(Attempt before, Attempt after, UUID managerId, UUID requestId);

	record Attempt(
			UUID attemptId, UUID organizationId, UUID cohortId, UUID projectId,
			UUID assessmentRoundId, UUID userId, UUID classroomId,
			String validityStatus, String decisionReasonCode, String decisionNote,
			int rowVersion, OffsetDateTime reviewedAt) {
	}
}
