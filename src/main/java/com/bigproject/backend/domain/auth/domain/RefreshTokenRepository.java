package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
	void save(RefreshToken refreshToken);

	Optional<RefreshTokenLineage> revokeForReplacement(
			UUID userId,
			UUID organizationId,
			Instant revokedAt
	);

	void revokeByTokenHash(String tokenHash, Instant revokedAt);

	Optional<RefreshTokenSession> findActiveByTokenHash(String tokenHash, Instant usedAt);

	void updateLastUsed(UUID tokenId, Instant usedAt, TokenRequestMetadata requestMetadata);
}
