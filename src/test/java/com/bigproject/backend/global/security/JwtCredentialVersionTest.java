package com.bigproject.backend.global.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtCredentialVersionTest {
	private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";

	@Test
	void preservesPasswordChangedAtInAccessToken() {
		JwtProvider provider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
		Instant passwordChangedAt = Instant.now().minusSeconds(10);

		String token = provider.createAccessToken(
				"user@example.com",
				"MANAGER",
				UUID.randomUUID(),
				passwordChangedAt
		);

		assertThat(provider.getPasswordChangedAt(token).toEpochMilli())
				.isEqualTo(passwordChangedAt.toEpochMilli());
	}
}
