package com.bigproject.backend.domain.notification.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManagerNotificationRepository {
	List<InboxRow> findInbox(UUID managerId, UUID cohortId);

	List<ReminderTarget> findEligibleTargets(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID teamId, UUID traineeId, String reasonCode);

	Optional<ReminderBatch> findReminderBatch(UUID organizationId, UUID managerId, String idempotencyKey);

	void lockIdempotencyKey(UUID organizationId, UUID managerId, String idempotencyKey);

	void insertReminder(
			UUID dispatchId, UUID batchId, UUID organizationId, UUID managerId,
			UUID assessmentRoundId, UUID teamId, UUID userId, String reasonCode,
			String idempotencyKey, String fingerprint, UUID requestId);

	record InboxRow(
			String itemId, String itemType, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId, UUID traineeId, String subject,
			String sourceStatus, String reasonCode, String evidence,
			OffsetDateTime deadlineAt, OffsetDateTime occurredAt, boolean resolved,
			boolean reminderEligible) {
	}

	record ReminderTarget(UUID userId, UUID teamId) {
	}

	record ReminderBatch(UUID batchId, String fingerprint, List<Dispatch> dispatches) {
	}

	record Dispatch(UUID dispatchId, UUID userId, String status) {
	}
}
