package com.bigproject.backend.domain.notification.application;

import com.bigproject.backend.domain.notification.domain.ManagerNotificationRepository;
import com.bigproject.backend.domain.notification.domain.NotificationErrorCode;
import com.bigproject.backend.domain.notification.presentation.dto.SendReminderRequest;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerNotificationServiceTest {
	private final ManagerViewScopeGuard scopeGuard = mock(ManagerViewScopeGuard.class);
	private final ManagerNotificationRepository repository = mock(ManagerNotificationRepository.class);
	private final ManagerNotificationService service = new ManagerNotificationService(scopeGuard, repository);
	private final UUID managerId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();

	@BeforeEach
	void scope() {
		when(scopeGuard.requireCohort(anyString(), eq(cohortId)))
				.thenReturn(new ManagerViewScopeGuard.ManagerActor(managerId, organizationId));
	}

	@Test
	void sortsDeadlinesBeforeItemsWithoutDeadline() {
		OffsetDateTime now = OffsetDateTime.now();
		var noDeadline = row("no-deadline", null, now.plusHours(2));
		var deadline = row("deadline", now.plusDays(1), now);
		when(repository.findInbox(managerId, cohortId)).thenReturn(List.of(noDeadline, deadline));

		var response = service.findInbox("manager@example.com", cohortId, null, null,
				null, false, null, 20);

		assertThat(response.items()).extracting(item -> item.itemId())
				.containsExactly("deadline", "no-deadline");
	}

	@Test
	void rejectsAnIdempotencyKeyUsedForAnotherPayload() {
		UUID roundId = UUID.randomUUID();
		UUID traineeId = UUID.randomUUID();
		var request = new SendReminderRequest(roundId, null, traineeId,
				SendReminderRequest.ReminderReason.INDIVIDUAL_ASSESSMENT_NOT_STARTED);
		when(repository.findReminderBatch(organizationId, managerId, "same-key"))
				.thenReturn(Optional.of(new ManagerNotificationRepository.ReminderBatch(
						UUID.randomUUID(), "different-fingerprint", List.of())));

		assertThatThrownBy(() -> service.sendReminder("manager@example.com", cohortId, "same-key", request))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.IDEMPOTENCY_KEY_REUSED));
		verify(repository, never()).insertReminder(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	private ManagerNotificationRepository.InboxRow row(
			String id, OffsetDateTime deadline, OffsetDateTime occurred) {
		return new ManagerNotificationRepository.InboxRow(id, "ATTENDANCE", null, null, null,
				null, null, "subject", "PENDING", null, null, deadline, occurred, false, true);
	}
}
