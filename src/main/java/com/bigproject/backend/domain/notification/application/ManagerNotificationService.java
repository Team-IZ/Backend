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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManagerNotificationService {
	private final ManagerViewScopeGuard scopeGuard;
	private final ManagerNotificationRepository repository;

	@Transactional(readOnly = true)
	public NotificationInboxResponse findInbox(
			String email, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			OffsetDateTime since, boolean includeResolved, String cursor, int size) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		String afterId = decodeCursor(cursor);
		Comparator<ManagerNotificationRepository.InboxRow> order = Comparator
				.comparing(ManagerNotificationRepository.InboxRow::deadlineAt,
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
		return new NotificationInboxResponse(page.stream().map(NotificationInboxResponse.InboxItem::from).toList(), next);
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
