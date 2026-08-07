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
		ResponseCookie cookie = manager(true, "None").create("refresh-jwt");

		assertThat(cookie.getName()).isEqualTo("refresh_token");
		assertThat(cookie.getValue()).isEqualTo("refresh-jwt");
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/api/v0/auth");
		assertThat(cookie.getSameSite()).isEqualTo("None");
		assertThat(cookie.isSecure()).isTrue();
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMillis(604_800_000));
	}

	/**
	 * {@code Lax}는 다른 사이트에서 보내는 fetch/XHR에 쿠키를 싣지 않는다. 프론트(Vercel·localhost:5173)와
	 * 백엔드(railway.app)는 서로 다른 사이트라, {@code Lax}면 로그인은 성공하지만
	 * {@code POST /auth/refresh}에서 브라우저가 쿠키를 빼고 보내 재발급이 전면 불가해진다.
	 * Swagger UI는 백엔드와 같은 사이트라 이 결함이 드러나지 않았다.
	 */
	@Test
	void 기본값은_다른_사이트에서도_실리는_SameSite_None이다() {
		JwtProvider jwtProvider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
		// 애플리케이션 기본값(application.yaml의 환경변수 기본값)과 같은 조합.
		RefreshTokenCookieManager manager =
				new RefreshTokenCookieManager(jwtProvider, "refresh_token", "/api/v0/auth", true, "None");

		assertThat(manager.create("refresh-jwt").getSameSite()).isEqualTo("None");
	}

	/**
	 * 브라우저는 Secure 없는 {@code SameSite=None} 쿠키를 <b>조용히 버린다.</b> 오류도 경고도 없이
	 * 재발급만 실패하므로, 설정이 어긋나면 여기서 Secure를 켜 준다.
	 */
	@Test
	void SameSite_None에는_Secure를_강제한다() {
		ResponseCookie cookie = manager(false, "None").create("refresh-jwt");

		assertThat(cookie.isSecure()).isTrue();
	}

	@Test
	void Lax로_되돌리면_Secure를_강제하지_않는다() {
		// http로 띄우는 로컬 백엔드에서 테스트할 여지를 남긴다.
		ResponseCookie cookie = manager(false, "Lax").create("refresh-jwt");

		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.isSecure()).isFalse();
	}

	@Test
	void clearsRefreshTokenCookieOnLogout() {
		ResponseCookie cookie = manager(true, "None").clear();

		assertThat(cookie.getName()).isEqualTo("refresh_token");
		assertThat(cookie.getValue()).isEmpty();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/api/v0/auth");
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
		// 굽던 것과 속성이 다르면 브라우저가 다른 쿠키로 보고 남겨 둔다.
		assertThat(cookie.getSameSite()).isEqualTo("None");
		assertThat(cookie.isSecure()).isTrue();
	}

	private RefreshTokenCookieManager manager(boolean secure, String sameSite) {
		JwtProvider jwtProvider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
		return new RefreshTokenCookieManager(jwtProvider, "refresh_token", "/api/v0/auth", secure, sameSite);
	}
}
