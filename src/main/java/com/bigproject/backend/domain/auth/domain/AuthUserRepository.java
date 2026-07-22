package com.bigproject.backend.domain.auth.domain;

import java.util.Optional;

public interface AuthUserRepository {
	Optional<AuthUser> findByNormalizedEmail(String normalizedEmail);
}
