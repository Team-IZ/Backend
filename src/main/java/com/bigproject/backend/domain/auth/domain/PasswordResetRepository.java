package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.InvitationPurpose;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetRepository {
	Optional<PasswordResetAccount> findAccountByNormalizedEmail(String normalizedEmail);

	boolean hasRecentRequest(UUID userId, Instant requestedAfter);

	void saveResetToken(
			UUID tokenId,
			PasswordResetAccount account,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String requestId
	);

	void invalidatePreviousResetTokens(UUID userId, UUID replacementTokenId, Instant invalidatedAt);

	void saveReplacementInvitationToken(
			UUID tokenId,
			PasswordResetAccount account,
			InvitationPurpose purpose,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String requestId
	);

	void replaceCurrentInvitationToken(UUID invitationId, UUID tokenId, Instant sentAt);

	Optional<PasswordResetToken> findTokenForUpdate(String tokenHash);

	Optional<PasswordResetToken> findToken(String tokenHash);

	boolean updatePassword(UUID userId, String passwordHash, Instant changedAt);

	boolean markTokenUsed(UUID tokenId, String requestId, Instant usedAt);

	void invalidateOtherResetTokens(UUID userId, UUID usedTokenId, Instant invalidatedAt);

	void revokeAllRefreshTokens(UUID userId, Instant revokedAt);
}
