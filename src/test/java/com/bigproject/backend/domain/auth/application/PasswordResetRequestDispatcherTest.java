package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.member.application.InvitationMailSender;
import com.bigproject.backend.domain.member.application.OneTimeTokenGenerator;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetRequestDispatcherTest {
	private static final String REQUEST_ID = UUID.randomUUID().toString();

	private final PasswordResetRepository repository = mock(PasswordResetRepository.class);
	private final PasswordResetMailSender resetMailSender = mock(PasswordResetMailSender.class);
	private final InvitationMailSender invitationMailSender = mock(InvitationMailSender.class);
	private final PasswordResetAuditLogger auditLogger = mock(PasswordResetAuditLogger.class);
	private final PasswordResetRequestDispatcher dispatcher = new PasswordResetRequestDispatcher(
			repository,
			resetMailSender,
			invitationMailSender,
			new OneTimeTokenGenerator(),
			new OneTimeTokenHasher(),
			auditLogger,
			Duration.ofMinutes(30),
			Duration.ofMinutes(1),
			Duration.ofHours(24)
	);

	@Test
	void issuesResetTokenAndMailForActiveAccount() {
		PasswordResetAccount account = account("ACTIVE", Role.MANAGER, null);
		when(repository.findAccountByNormalizedEmail(account.normalizedEmail())).thenReturn(Optional.of(account));
		when(repository.hasRecentRequest(eq(account.userId()), any())).thenReturn(false);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository).saveResetToken(any(), eq(account), any(), any(), any(), eq(REQUEST_ID));
		verify(repository).invalidatePreviousResetTokens(eq(account.userId()), any(), any());
		verify(resetMailSender).sendResetLink(eq(account), any(), any());
	}

	@Test
	void suppressesDuplicateRequestDuringCooldown() {
		PasswordResetAccount account = account("ACTIVE", Role.MANAGER, null);
		when(repository.findAccountByNormalizedEmail(account.normalizedEmail())).thenReturn(Optional.of(account));
		when(repository.hasRecentRequest(eq(account.userId()), any())).thenReturn(true);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository, never()).saveResetToken(any(), any(), any(), any(), any(), any());
		verify(resetMailSender, never()).sendResetLink(any(), any(), any());
	}

	@Test
	void sendsInactiveAccountNoticeWithoutResetToken() {
		PasswordResetAccount account = account("INACTIVE", Role.TRAINEE, null);
		when(repository.findAccountByNormalizedEmail(account.normalizedEmail())).thenReturn(Optional.of(account));

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(resetMailSender).sendInactiveAccountNotice(account);
		verify(repository, never()).saveResetToken(any(), any(), any(), any(), any(), any());
	}

	private PasswordResetAccount account(String status, Role role, UUID invitationId) {
		return new PasswordResetAccount(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"기관",
				"user@example.com",
				"홍길동",
				"user@example.com",
				"hash",
				status,
				role,
				invitationId,
				null,
				null
		);
	}
}
