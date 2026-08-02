package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.member.application.InvitationMailSender;
import com.bigproject.backend.domain.member.application.OneTimeTokenGenerator;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetRequestDispatcher {
	private final PasswordResetRepository repository;
	private final PasswordResetMailSender passwordResetMailSender;
	private final InvitationMailSender invitationMailSender;
	private final OneTimeTokenGenerator tokenGenerator;
	private final OneTimeTokenHasher tokenHasher;
	private final Duration resetExpiration;
	private final Duration requestCooldown;
	private final Duration invitationExpiration;
	private final PasswordResetAuditLogger auditLogger;

	public PasswordResetRequestDispatcher(
			PasswordResetRepository repository,
			PasswordResetMailSender passwordResetMailSender,
			InvitationMailSender invitationMailSender,
			OneTimeTokenGenerator tokenGenerator,
			OneTimeTokenHasher tokenHasher,
			PasswordResetAuditLogger auditLogger,
			@Value("${password-reset.expiration:PT30M}") Duration resetExpiration,
			@Value("${password-reset.request-cooldown:PT1M}") Duration requestCooldown,
			@Value("${invitation.expiration:PT24H}") Duration invitationExpiration
	) {
		this.repository = repository;
		this.passwordResetMailSender = passwordResetMailSender;
		this.invitationMailSender = invitationMailSender;
		this.tokenGenerator = tokenGenerator;
		this.tokenHasher = tokenHasher;
		this.auditLogger = auditLogger;
		this.resetExpiration = resetExpiration;
		this.requestCooldown = requestCooldown;
		this.invitationExpiration = invitationExpiration;
	}

	@Transactional
	public void dispatch(String normalizedEmail, String requestId) {
		Optional<PasswordResetAccount> candidate = repository.findAccountByNormalizedEmail(normalizedEmail);
		if (candidate.isEmpty()) {
			auditLogger.recordRequestSuccess(null, requestId);
			return;
		}
		PasswordResetAccount account = candidate.get();
		if (repository.hasRecentRequest(account.userId(), Instant.now().minus(requestCooldown))) {
			return;
		}
		auditLogger.recordRequestSuccess(account, requestId);
		switch (account.status()) {
			case "ACTIVE" -> sendReset(account, requestId);
			case "PENDING" -> sendActivation(account, requestId);
			case "INACTIVE" -> passwordResetMailSender.sendInactiveAccountNotice(account);
			default -> {
				// Unknown lifecycle states deliberately keep the public response indistinguishable.
			}
		}
	}

	private void sendReset(PasswordResetAccount account, String requestId) {
		Instant now = Instant.now();
		String rawToken = tokenGenerator.generate();
		UUID tokenId = UUID.randomUUID();
		Instant expiresAt = now.plus(resetExpiration);
		repository.saveResetToken(tokenId, account, tokenHasher.hash(rawToken), now, expiresAt, requestId);
		repository.invalidatePreviousResetTokens(account.userId(), tokenId, now);
		passwordResetMailSender.sendResetLink(account, rawToken, expiresAt);
	}

	private void sendActivation(PasswordResetAccount account, String requestId) {
		if (account.invitationId() == null) {
			return;
		}
		Instant now = Instant.now();
		Instant expiresAt = now.plus(invitationExpiration);
		String rawToken = tokenGenerator.generate();
		UUID tokenId = UUID.randomUUID();
		InvitationPurpose purpose = account.role() == Role.TRAINEE
				? InvitationPurpose.INVITE_TRAINEE
				: InvitationPurpose.INVITE_OPERATOR_MANAGER;
		repository.saveReplacementInvitationToken(
				tokenId,
				account,
				purpose,
				tokenHasher.hash(rawToken),
				now,
				expiresAt,
				requestId
		);
		repository.replaceCurrentInvitationToken(account.invitationId(), tokenId, now);
		PendingInvitation invitation = new PendingInvitation(
				account.userId(),
				account.invitationId(),
				tokenId,
				account.email(),
				rawToken,
				account.role(),
				now,
				expiresAt,
				new InvitationContext(
						account.organizationId(),
						account.organizationName(),
						account.cohortId(),
						account.cohortName()
				)
		);
		if (account.role() == Role.TRAINEE) {
			invitationMailSender.sendTraineeInvitation(invitation, account.name());
		} else {
			invitationMailSender.sendManagerInvitation(invitation);
		}
	}
}
