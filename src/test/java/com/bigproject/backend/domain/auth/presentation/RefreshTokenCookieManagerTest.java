package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCookieManagerTest {
	private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";

	@Test
	void createsHttpOnlyRefreshTokenCookie() {
		JwtProvider jwtProvider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
		RefreshTokenCookieManager manager = new RefreshTokenCookieManager(
				jwtProvider,
				"refresh_token",
				"/api/v0/auth",
				false,
				"Lax"
		);

		ResponseCookie cookie = manager.create("refresh-jwt");

		assertThat(cookie.getName()).isEqualTo("refresh_token");
		assertThat(cookie.getValue()).isEqualTo("refresh-jwt");
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/api/v0/auth");
		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMillis(604_800_000));
	}

	@Test
	void clearsRefreshTokenCookieOnLogout() {
		JwtProvider jwtProvider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
		RefreshTokenCookieManager manager = new RefreshTokenCookieManager(
				jwtProvider,
				"refresh_token",
				"/api/v0/auth",
				false,
				"Lax"
		);

		ResponseCookie cookie = manager.clear();

		assertThat(cookie.getName()).isEqualTo("refresh_token");
		assertThat(cookie.getValue()).isEmpty();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/api/v0/auth");
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
	}
}
