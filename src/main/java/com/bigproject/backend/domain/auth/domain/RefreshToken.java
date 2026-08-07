package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record RefreshToken(
		UUID tokenId,
		UUID userId,
		String tokenHash,
		UUID tokenFamilyId,
		UUID parentTokenId,
		Instant issuedAt,
		Instant expiresAt,
		String issuedIp,
		String issuedUserAgent
) {
	public static RefreshToken issue(
			UUID userId,
			String tokenHash,
			Instant expiresAt,
			TokenRequestMetadata requestMetadata,
			Optional<RefreshTokenLineage> previousLineage
	) {
		return new RefreshToken(
				UUID.randomUUID(),
				userId,
				tokenHash,
				previousLineage.map(RefreshTokenLineage::tokenFamilyId)
						.orElseGet(UUID::randomUUID),
				previousLineage.map(RefreshTokenLineage::parentTokenId).orElse(null),
				Instant.now(),
				expiresAt,
				requestMetadata.ipAddress(),
				requestMetadata.userAgent()
		);
	}
}
