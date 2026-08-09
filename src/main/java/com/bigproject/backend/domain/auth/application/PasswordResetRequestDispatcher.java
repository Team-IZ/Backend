package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.member.application.OneTimeTokenGenerator;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
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
	private final InvitationResendDispatcher invitationResendDispatcher;
	private final OneTimeTokenGenerator tokenGenerator;
	private final OneTimeTokenHasher tokenHasher;
	private final Duration resetExpiration;
	private final Duration requestCooldown;
	private final PasswordResetAuditLogger auditLogger;

	public PasswordResetRequestDispatcher(
			PasswordResetRepository repository,
			PasswordResetMailSender passwordResetMailSender,
			InvitationResendDispatcher invitationResendDispatcher,
			OneTimeTokenGenerator tokenGenerator,
			OneTimeTokenHasher tokenHasher,
			PasswordResetAuditLogger auditLogger,
			@Value("${password-reset.expiration:PT30M}") Duration resetExpiration,
			@Value("${password-reset.request-cooldown:PT1M}") Duration requestCooldown
	) {
		this.repository = repository;
		this.passwordResetMailSender = passwordResetMailSender;
		this.invitationResendDispatcher = invitationResendDispatcher;
		this.tokenGenerator = tokenGenerator;
		this.tokenHasher = tokenHasher;
		this.auditLogger = auditLogger;
		this.resetExpiration = resetExpiration;
		this.requestCooldown = requestCooldown;
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
			case "PENDING" -> invitationResendDispatcher.resend(account, requestId);
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
}
