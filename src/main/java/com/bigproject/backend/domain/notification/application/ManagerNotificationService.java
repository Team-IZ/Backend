package com.bigproject.backend.domain.notification.application;

import com.bigproject.backend.domain.notification.domain.ManagerNotificationRepository;
import com.bigproject.backend.domain.notification.domain.NotificationErrorCode;
import com.bigproject.backend.domain.notification.presentation.dto.NotificationInboxResponse;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderRequest;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManagerNotificationService {
	private final ManagerViewScopeGuard scopeGuard;
	private final ManagerNotificationRepository repository;

	/**
	 * MG-01 정렬 규칙(구현 근거 스펙) — "① 창이 닫히기 직전 → ② 확인해야 판정이 확정되는 것
	 * → ③ 면담 대기 → ④ 미제출" 네 그룹 우선순위다. 그룹 안에서는 마감 임박순으로 세부 정렬한다.
	 *
	 * <p>{@code ASSESSMENT}(reasonCode 없는 진행 중 응시)는 스펙에 별도 언급이 없어 마지막 그룹(5)으로
	 * 둔다 — 매니저가 지금 당장 할 일이 없는 상태라 뒤로 밀려도 스펙 의도와 어긋나지 않는다.
	 */
	private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
			"ASSESSMENT_NOT_STARTED", 1,
			"REVIEW", 1,
			"INVALID_ATTEMPT", 2,
			"INTERVIEW", 3,
			"SUBMISSION_MISSING", 4,
			"ANALYSIS_FAILED", 4
	);

	@Transactional(readOnly = true)
	public NotificationInboxResponse findInbox(
			String email, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			OffsetDateTime since, boolean includeResolved, String cursor, int size) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		String afterId = decodeCursor(cursor);
		/*
		 * 34차 R9 — 밴드가 1차 정렬 기준이다.
		 *
		 * 「서버가 급한 순을 정해 주면 화면이 정렬하지 않겠다」는 요청이라, 화면이 섹션을 나누는
		 * 기준과 목록 순서가 어긋나면 안 된다. 밴드를 앞에 두고 종전 기준(유형 → 마감 → 발생)을
		 * 밴드 안의 순서로 남긴다 — 같은 밴드 안에서는 지금까지와 같은 순서다.
		 *
		 * now를 한 번만 읽어 정렬과 응답이 같은 시각을 본다. 행마다 새로 읽으면 자정 경계에서
		 * 정렬 기준과 실린 밴드가 갈릴 수 있다.
		 */
		OffsetDateTime now = OffsetDateTime.now();
		Comparator<ManagerNotificationRepository.InboxRow> order = Comparator
				.<ManagerNotificationRepository.InboxRow, Integer>comparing(
						row -> NotificationInboxResponse.InboxItem.bandOf(row.deadlineAt(), now))
				.thenComparing(row -> TYPE_PRIORITY.getOrDefault(row.itemType(), 5))
				.thenComparing(ManagerNotificationRepository.InboxRow::deadlineAt,
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(ManagerNotificationRepository.InboxRow::occurredAt, Comparator.reverseOrder())
				.thenComparing(ManagerNotificationRepository.InboxRow::itemId);
		List<ManagerNotificationRepository.InboxRow> filtered = repository.findInbox(actor.userId(), cohortId).stream()
				.filter(row -> projectId == null || projectId.equals(row.projectId()))
				.filter(row -> assessmentRoundId == null || assessmentRoundId.equals(row.assessmentRoundId()))
				.filter(row -> since == null || !row.occurredAt().isBefore(since))
				.filter(row -> includeResolved || !row.resolved())
				.sorted(order)
				.toList();
		if (afterId != null) {
			int index = -1;
			for (int i = 0; i < filtered.size(); i++) if (afterId.equals(filtered.get(i).itemId())) { index = i; break; }
			if (index < 0) throw new ApiException(NotificationErrorCode.INBOX_CURSOR_INVALID);
			filtered = filtered.subList(index + 1, filtered.size());
		}
		boolean hasNext = filtered.size() > size;
		List<ManagerNotificationRepository.InboxRow> page = filtered.subList(0, Math.min(size, filtered.size()));
		String next = hasNext && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1).itemId()) : null;
		return new NotificationInboxResponse(
				page.stream().map(row -> NotificationInboxResponse.InboxItem.from(row, now)).toList(), next);
	}

	@Transactional
	public SendReminderResponse sendReminder(
			String email, UUID cohortId, String idempotencyKey, SendReminderRequest request) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		validateTarget(request);
		repository.lockIdempotencyKey(actor.organizationId(), actor.userId(), idempotencyKey);
		String fingerprint = sha256(request.assessmentRoundId() + "|" + request.teamId() + "|"
				+ request.traineeId() + "|" + request.reasonCode());
		var existing = repository.findReminderBatch(actor.organizationId(), actor.userId(), idempotencyKey);
		if (existing.isPresent()) {
			if (!fingerprint.equals(existing.get().fingerprint())) {
				throw new ApiException(NotificationErrorCode.IDEMPOTENCY_KEY_REUSED);
			}
			return response(existing.get());
		}
		List<ManagerNotificationRepository.ReminderTarget> targets = repository.findEligibleTargets(
				actor.userId(), cohortId, request.assessmentRoundId(), request.teamId(), request.traineeId(), request.reasonCode().name());
		if (targets.isEmpty()) throw new ApiException(NotificationErrorCode.REMINDER_TARGET_NOT_ELIGIBLE);
		UUID batchId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		List<ManagerNotificationRepository.Dispatch> dispatches = targets.stream().map(target -> {
			UUID dispatchId = UUID.randomUUID();
			repository.insertReminder(dispatchId, batchId, actor.organizationId(), actor.userId(),
					request.assessmentRoundId(), target.teamId(), target.userId(), request.reasonCode().name(),
					idempotencyKey, fingerprint, requestId);
			return new ManagerNotificationRepository.Dispatch(dispatchId, target.userId(), "PENDING");
		}).toList();
		return response(new ManagerNotificationRepository.ReminderBatch(batchId, fingerprint, dispatches));
	}

	private void validateTarget(SendReminderRequest request) {
		if ((request.teamId() == null) == (request.traineeId() == null)) {
			throw new ApiException(NotificationErrorCode.REMINDER_TARGET_INVALID);
		}
		boolean teamReason = request.reasonCode() != SendReminderRequest.ReminderReason.INDIVIDUAL_ASSESSMENT_NOT_STARTED;
		if (teamReason != (request.teamId() != null)) {
			throw new ApiException(NotificationErrorCode.REMINDER_REASON_INVALID);
		}
	}

	private SendReminderResponse response(ManagerNotificationRepository.ReminderBatch batch) {
		return new SendReminderResponse(batch.batchId(), batch.dispatches().stream()
				.map(row -> new SendReminderResponse.Dispatch(row.dispatchId(), row.userId(), row.status())).toList());
	}

	private String encodeCursor(String itemId) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(itemId.getBytes(StandardCharsets.UTF_8));
	}

	private String decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) return null;
		try { return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8); }
		catch (IllegalArgumentException exception) { throw new ApiException(NotificationErrorCode.INBOX_CURSOR_INVALID); }
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}