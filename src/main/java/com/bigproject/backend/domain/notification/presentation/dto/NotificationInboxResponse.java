package com.bigproject.backend.domain.notification.presentation.dto;

import com.bigproject.backend.domain.notification.domain.ManagerNotificationRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "매니저 대시보드 인박스")
public record NotificationInboxResponse(List<InboxItem> items, String nextCursor) {
	public record InboxItem(
			String itemId, String itemType, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId, UUID traineeId, String subject,
			String sourceStatus, String reasonCode, String evidence,
			OffsetDateTime deadlineAt, OffsetDateTime occurredAt,
			boolean resolved, boolean reminderEligible) {
		public static InboxItem from(ManagerNotificationRepository.InboxRow row) {
			return new InboxItem(row.itemId(), row.itemType(), row.projectId(), row.assessmentRoundId(),
					row.classroomId(), row.teamId(), row.traineeId(), row.subject(), row.sourceStatus(),
					row.reasonCode(), row.evidence(), row.deadlineAt(), row.occurredAt(),
					row.resolved(), row.reminderEligible());
		}
	}
}
