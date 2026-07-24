package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthUserRepository {
	Optional<AuthUser> findByNormalizedEmail(String normalizedEmail);

	void updateLastLoginAt(UUID userId, Instant lastLoginAt);
}
