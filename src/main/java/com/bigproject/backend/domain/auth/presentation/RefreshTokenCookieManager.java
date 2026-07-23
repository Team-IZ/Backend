package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.global.security.JwtProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

@Component
public class RefreshTokenCookieManager {
	private final JwtProvider jwtProvider;
	private final String cookieName;
	private final String cookiePath;
	private final boolean secure;
	private final String sameSite;

	public RefreshTokenCookieManager(
			JwtProvider jwtProvider,
			@Value("${auth.refresh-cookie.name:refresh_token}") String cookieName,
			@Value("${auth.refresh-cookie.path:/api/v0/auth}") String cookiePath,
			@Value("${auth.refresh-cookie.secure:false}") boolean secure,
			@Value("${auth.refresh-cookie.same-site:Lax}") String sameSite
	) {
		this.jwtProvider = jwtProvider;
		this.cookieName = cookieName;
		this.cookiePath = cookiePath;
		this.secure = secure;
		this.sameSite = sameSite;
	}

	public ResponseCookie create(String refreshToken) {
		return ResponseCookie.from(cookieName, refreshToken)
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path(cookiePath)
				.maxAge(Duration.ofMillis(jwtProvider.getRefreshTokenExpiration()))
				.build();
	}

	public String resolve(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		return Arrays.stream(cookies)
				.filter(cookie -> cookieName.equals(cookie.getName()))
				.map(Cookie::getValue)
				.findFirst()
				.orElse(null);
	}

	public ResponseCookie clear() {
		return ResponseCookie.from(cookieName, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path(cookiePath)
				.maxAge(Duration.ZERO)
				.build();
	}
}
