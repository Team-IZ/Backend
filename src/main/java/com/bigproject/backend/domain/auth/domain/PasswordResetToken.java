package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record PasswordResetToken(
		UUID tokenId,
		UUID userId,
		UUID organizationId,
		String email,
		String normalizedEmail,
		String passwordHash,
		String userStatus,
		String purpose,
		Instant expiresAt,
		Instant usedAt,
		Instant invalidatedAt
) {
}
