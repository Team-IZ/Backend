package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.member.application.InvitationMailSender;
import com.bigproject.backend.domain.member.application.OneTimeTokenGenerator;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
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

class InvitationResendDispatcherTest {
	private static final String REQUEST_ID = UUID.randomUUID().toString();

	private final PasswordResetRepository repository = mock(PasswordResetRepository.class);
	private final InvitationMailSender invitationMailSender = mock(InvitationMailSender.class);
	private final PasswordResetAuditLogger auditLogger = mock(PasswordResetAuditLogger.class);
	private final InvitationResendDispatcher dispatcher = new InvitationResendDispatcher(
			repository,
			invitationMailSender,
			new OneTimeTokenGenerator(),
			new OneTimeTokenHasher(),
			auditLogger,
			Duration.ofHours(24),
			Duration.ofMinutes(1)
	);

	/**
	 * 목적이 역할과 어긋나면 재발송 링크가 수락 단계에서 항상 400으로 죽는다.
	 * 슈퍼어드민은 무소속이라 오퍼레이터·매니저 경로로 해석되지 않는다.
	 */
	@Test
	void issuesSuperAdminPurposeTokenForSuperAdmin() {
		PasswordResetAccount account = pendingAccount(Role.SUPER_ADMIN);
		givenResendableAccount(account);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository).saveReplacementInvitationToken(
				any(), eq(account), eq(InvitationPurpose.INVITE_SUPER_ADMIN), any(), any(), any(), eq(REQUEST_ID)
		);
		verify(invitationMailSender).sendSuperAdminInvitation(any());
	}

	@Test
	void issuesTraineePurposeTokenForTrainee() {
		PasswordResetAccount account = pendingAccount(Role.TRAINEE);
		givenResendableAccount(account);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository).saveReplacementInvitationToken(
				any(), eq(account), eq(InvitationPurpose.INVITE_TRAINEE), any(), any(), any(), eq(REQUEST_ID)
		);
		verify(invitationMailSender).sendTraineeInvitation(any(), eq(account.name()));
	}

	@Test
	void issuesOperatorManagerPurposeTokenForManager() {
		PasswordResetAccount account = pendingAccount(Role.MANAGER);
		givenResendableAccount(account);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository).saveReplacementInvitationToken(
				any(), eq(account), eq(InvitationPurpose.INVITE_OPERATOR_MANAGER), any(), any(), any(), eq(REQUEST_ID)
		);
		verify(invitationMailSender).sendManagerInvitation(any());
	}

	@Test
	void skipsAlreadyActivatedAccount() {
		PasswordResetAccount account = account("ACTIVE", Role.MANAGER, UUID.randomUUID());
		givenResendableAccount(account);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository, never()).saveReplacementInvitationToken(
				any(), any(), any(), any(), any(), any(), any()
		);
		verify(invitationMailSender, never()).sendManagerInvitation(any());
	}

	@Test
	void suppressesResendDuringCooldown() {
		PasswordResetAccount account = pendingAccount(Role.MANAGER);
		when(repository.findAccountByNormalizedEmail(account.normalizedEmail())).thenReturn(Optional.of(account));
		when(repository.hasRecentRequest(eq(account.userId()), any())).thenReturn(true);

		dispatcher.dispatch(account.normalizedEmail(), REQUEST_ID);

		verify(repository, never()).saveReplacementInvitationToken(
				any(), any(), any(), any(), any(), any(), any()
		);
		verify(invitationMailSender, never()).sendManagerInvitation(any());
	}

	private void givenResendableAccount(PasswordResetAccount account) {
		when(repository.findAccountByNormalizedEmail(account.normalizedEmail())).thenReturn(Optional.of(account));
		when(repository.hasRecentRequest(eq(account.userId()), any())).thenReturn(false);
	}

	private PasswordResetAccount pendingAccount(Role role) {
		return account("PENDING", role, UUID.randomUUID());
	}

	private PasswordResetAccount account(String status, Role role, UUID invitationId) {
		return new PasswordResetAccount(
				UUID.randomUUID(),
				role == Role.SUPER_ADMIN ? null : UUID.randomUUID(),
				role == Role.SUPER_ADMIN ? null : "기관",
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
