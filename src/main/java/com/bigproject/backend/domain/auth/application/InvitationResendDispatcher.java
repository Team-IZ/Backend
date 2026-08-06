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
public class InvitationResendDispatcher {
	private final PasswordResetRepository repository;
	private final InvitationMailSender invitationMailSender;
	private final OneTimeTokenGenerator tokenGenerator;
	private final OneTimeTokenHasher tokenHasher;
	private final PasswordResetAuditLogger auditLogger;
	private final Duration invitationExpiration;
	private final Duration requestCooldown;

	public InvitationResendDispatcher(
			PasswordResetRepository repository,
			InvitationMailSender invitationMailSender,
			OneTimeTokenGenerator tokenGenerator,
			OneTimeTokenHasher tokenHasher,
			PasswordResetAuditLogger auditLogger,
			@Value("${invitation.expiration:PT24H}") Duration invitationExpiration,
			@Value("${password-reset.request-cooldown:PT1M}") Duration requestCooldown
	) {
		this.repository = repository;
		this.invitationMailSender = invitationMailSender;
		this.tokenGenerator = tokenGenerator;
		this.tokenHasher = tokenHasher;
		this.auditLogger = auditLogger;
		this.invitationExpiration = invitationExpiration;
		this.requestCooldown = requestCooldown;
	}

	@Transactional
	public void dispatch(String normalizedEmail, String requestId) {
		Optional<PasswordResetAccount> candidate = repository.findAccountByNormalizedEmail(normalizedEmail);
		if (candidate.isEmpty()) {
			auditLogger.recordResendSuccess(null, requestId);
			return;
		}
		PasswordResetAccount account = candidate.get();
		if (repository.hasRecentRequest(account.userId(), Instant.now().minus(requestCooldown))) {
			return;
		}
		auditLogger.recordResendSuccess(account, requestId);
		if ("PENDING".equals(account.status())) {
			resend(account, requestId);
		}
	}

	/**
	 * 초대 토큰을 새로 발급하고 초대 메일을 다시 보낸다.
	 *
	 * <p>기존 토큰은 REPLACED로 무효화되므로 <b>이전 링크는 즉시 죽는다.</b> 만료된 초대도 대상이며
	 * {@code user_invitation}은 PENDING·SENT·DELIVERY_FAILED·EXPIRED에서 SENT로 되돌아간다.
	 */
	@Transactional
	public void resend(PasswordResetAccount account, String requestId) {
		if (account.invitationId() == null) {
			return;
		}
		Instant now = Instant.now();
		Instant expiresAt = now.plus(invitationExpiration);
		String rawToken = tokenGenerator.generate();
		UUID tokenId = UUID.randomUUID();
		repository.saveReplacementInvitationToken(
				tokenId,
				account,
				purposeFor(account.role()),
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
		switch (account.role()) {
			case SUPER_ADMIN -> invitationMailSender.sendSuperAdminInvitation(invitation);
			case TRAINEE -> invitationMailSender.sendTraineeInvitation(invitation, account.name());
			default -> invitationMailSender.sendManagerInvitation(invitation);
		}
	}

	/**
	 * 목적이 역할과 어긋나면 수락 단계에서 초대가 해석되지 않는다.
	 * 슈퍼어드민에 INVITE_OPERATOR_MANAGER를 발급하면 재발송 링크가 항상 400으로 죽는다.
	 */
	private InvitationPurpose purposeFor(Role role) {
		return switch (role) {
			case SUPER_ADMIN -> InvitationPurpose.INVITE_SUPER_ADMIN;
			case TRAINEE -> InvitationPurpose.INVITE_TRAINEE;
			default -> InvitationPurpose.INVITE_OPERATOR_MANAGER;
		};
	}
}
