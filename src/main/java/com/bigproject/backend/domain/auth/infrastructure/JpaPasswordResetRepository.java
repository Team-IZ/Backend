package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.domain.PasswordResetToken;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuditLogJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.RefreshTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.UserInvitationJpaRepository;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaPasswordResetRepository implements PasswordResetRepository {
	private final AuthUserJpaRepository userRepository;
	private final OneTimeTokenJpaRepository tokenRepository;
	private final UserInvitationJpaRepository invitationRepository;
	private final RefreshTokenJpaRepository refreshTokenRepository;
	private final AuditLogJpaRepository auditLogRepository;

	@Override
	public Optional<PasswordResetAccount> findAccountByNormalizedEmail(String normalizedEmail) {
		return userRepository.findPasswordResetAccount(normalizedEmail).map(row -> new PasswordResetAccount(
				row.getUserId(),
				row.getOrganizationId(),
				row.getOrganizationName(),
				row.getEmail(),
				row.getName(),
				row.getNormalizedEmail(),
				row.getPasswordHash(),
				row.getStatus(),
				Role.valueOf(row.getRoleCode()),
				row.getInvitationId(),
				row.getCohortId(),
				row.getCohortName()
		));
	}

	@Override
	public boolean hasRecentRequest(UUID userId, Instant requestedAfter) {
		return auditLogRepository.hasRecentPasswordResetRequest(userId.toString(), requestedAfter);
	}

	@Override
	public void saveResetToken(
			UUID tokenId,
			PasswordResetAccount account,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String requestId
	) {
		insertToken(tokenId, account, null, InvitationPurpose.PASSWORD_RESET, tokenHash, issuedAt, expiresAt, requestId);
	}

	@Override
	public void invalidatePreviousResetTokens(UUID userId, UUID replacementTokenId, Instant invalidatedAt) {
		tokenRepository.invalidatePreviousResetTokens(userId, replacementTokenId, invalidatedAt);
	}

	@Override
	public void saveReplacementInvitationToken(
			UUID tokenId,
			PasswordResetAccount account,
			InvitationPurpose purpose,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String requestId
	) {
		insertToken(tokenId, account, account.invitationId(), purpose, tokenHash, issuedAt, expiresAt, requestId);
		tokenRepository.invalidatePreviousInvitationTokens(account.invitationId(), tokenId, issuedAt);
	}

	@Override
	public void replaceCurrentInvitationToken(UUID invitationId, UUID tokenId, Instant sentAt) {
		if (invitationRepository.replaceCurrentToken(invitationId, tokenId, sentAt) != 1) {
			throw new IllegalStateException("활성화 초대 토큰을 교체할 수 없습니다.");
		}
	}

	@Override
	public Optional<PasswordResetToken> findTokenForUpdate(String tokenHash) {
		return tokenRepository.findPasswordResetTokenForUpdate(tokenHash).map(row -> new PasswordResetToken(
				row.getTokenId(),
				row.getUserId(),
				row.getOrganizationId(),
				row.getEmail(),
				row.getNormalizedEmail(),
				row.getPasswordHash(),
				row.getUserStatus(),
				row.getPurpose(),
				row.getExpiresAt(),
				row.getUsedAt(),
				row.getInvalidatedAt()
		));
	}

	@Override
	public boolean updatePassword(UUID userId, String passwordHash, Instant changedAt) {
		return userRepository.updatePassword(userId, passwordHash, changedAt) == 1;
	}

	@Override
	public boolean markTokenUsed(UUID tokenId, String requestId, Instant usedAt) {
		return tokenRepository.markPasswordResetTokenUsed(tokenId, requestId, usedAt) == 1;
	}

	@Override
	public void invalidateOtherResetTokens(UUID userId, UUID usedTokenId, Instant invalidatedAt) {
		tokenRepository.invalidateOtherResetTokens(userId, usedTokenId, invalidatedAt);
	}

	@Override
	public void revokeAllRefreshTokens(UUID userId, Instant revokedAt) {
		refreshTokenRepository.revokeAllForPasswordChange(userId, revokedAt);
	}

	private void insertToken(
			UUID tokenId,
			PasswordResetAccount account,
			UUID invitationId,
			InvitationPurpose purpose,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String requestId
	) {
		int inserted = tokenRepository.insert(
				tokenId,
				account.organizationId(),
				account.userId(),
				invitationId,
				account.email(),
				account.normalizedEmail(),
				purpose.name(),
				tokenHash,
				"{}",
				issuedAt,
				expiresAt,
				requestId
		);
		if (inserted != 1) {
			throw new IllegalStateException("일회성 토큰을 저장할 수 없습니다.");
		}
	}
}
