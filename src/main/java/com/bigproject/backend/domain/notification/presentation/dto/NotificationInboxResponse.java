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
			boolean reminderEligible,

			@Schema(description = """
					**급한 정도. 1이 가장 급하다**(34차 R9).

					| 밴드 | 뜻 |
					|---|---|
					| `1` | 마감이 **이미 지났다** |
					| `2` | **오늘 안에** 마감 |
					| `3` | 마감이 남아 있다 |
					| `4` | **마감이 없는 항목**(면담·무효 검토 등) |

					`items[]`는 이미 이 순서로 옵니다 — 밴드가 이른 순, 같은 밴드 안에서는
					마감이 이른 순입니다. **화면이 다시 정렬하지 않아도 됩니다.** 밴드는
                    섹션을 나눠 그릴 때 쓰시면 됩니다.

					마감(`deadlineAt`)이 유일한 기준입니다. 항목 유형은 보지 않습니다 —
					같은 유형이라도 마감이 지난 것과 남은 것은 급한 정도가 다르고, 유형별
					경중은 화면이 정할 몫이라 서버가 겹쳐 정하지 않습니다.""", example = "2")
			int band) {
		public static InboxItem from(ManagerNotificationRepository.InboxRow row, OffsetDateTime now) {
			return new InboxItem(row.itemId(), row.itemType(), row.projectId(), row.assessmentRoundId(),
					row.classroomId(), row.teamId(), row.traineeId(), row.subject(), row.sourceStatus(),
					row.reasonCode(), row.evidence(), row.deadlineAt(), row.occurredAt(),
					row.resolved(), row.reminderEligible(), bandOf(row.deadlineAt(), now));
		}

		/**
		 * 마감 하나로 밴드를 정한다.
		 *
		 * <p>「오늘 안」의 경계를 <b>절대 시각(24시간)</b>이 아니라 <b>날짜</b>로 잡는다. 매니저는
		 * 하루를 단위로 일하므로 「오늘 마감」과 「내일 마감」 사이가 경계이지, 지금으로부터 24시간이
		 * 경계가 아니다. 오후 6시에 열든 오전 9시에 열든 같은 항목이 같은 밴드에 있어야 한다.
		 */
		public static int bandOf(OffsetDateTime deadlineAt, OffsetDateTime now) {
			if (deadlineAt == null) {
				return 4;
			}
			if (!deadlineAt.isAfter(now)) {
				return 1;
			}
			return deadlineAt.toLocalDate().isEqual(now.toLocalDate()) ? 2 : 3;
		}
	}
}
