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
			@Schema(description = "이미 해소된 항목인가. 기본 조회에서는 빠지고 `includeResolved=true`일 때만 섞여 온다")
			boolean resolved,
			@Schema(description = """
					지금 독촉을 보낼 수 있는 항목인가. **`resolved`의 반대가 아니다** —
					해소되지 않았어도 독촉 대상이 아닌 항목(무효 검토·인터뷰)이 있어 둘을 따로 읽어야 한다.""")
			boolean reminderEligible) {
		public static InboxItem from(ManagerNotificationRepository.InboxRow row) {
			return new InboxItem(row.itemId(), row.itemType(), row.projectId(), row.assessmentRoundId(),
					row.classroomId(), row.teamId(), row.traineeId(), row.subject(), row.sourceStatus(),
					row.reasonCode(), row.evidence(), row.deadlineAt(), row.occurredAt(),
					row.resolved(), row.reminderEligible());
		}
	}
}
