package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.global.security.JwtProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/**
 * 리프레시 토큰 쿠키를 굽고 읽고 지운다.
 *
 * <p><b>기본값이 {@code SameSite=None; Secure}인 이유.</b> 전에는 {@code Lax}였고, 그래서
 * 재발급이 아예 동작하지 않았다 — {@code Lax}는 <b>다른 사이트에서 보내는 fetch/XHR에 쿠키를 싣지 않고</b>
 * 최상위 페이지 이동에만 붙는다. 프론트(localhost:5173 · Vercel)와 백엔드(railway.app)는 서로 다른
 * 사이트이므로 로그인은 성공해 쿠키가 저장되지만 {@code POST /auth/refresh}에서 브라우저가 쿠키를
 * 빼고 보내 401로 끝났다. 배포 환경만의 문제가 아니라 로컬 개발에서도 똑같이 막힌다.
 *
 * <p>Swagger UI는 백엔드와 <b>같은 사이트</b>라 {@code Lax}로도 쿠키가 실린다. 그래서 이 결함은
 * 브라우저에서 프론트를 띄워 봐야만 드러났다.
 *
 * <p><b>CSRF는 Origin 검사가 막는다.</b> 보통 {@code SameSite=None}은 CSRF 노출을 키우지만,
 * 이 서버는 로그인·재발급·로그아웃에서 {@code LoginClientValidator}로 Origin을 직접 검사해
 * 허용 목록에 없으면 {@code 403 LOGIN_ORIGIN_NOT_ALLOWED}로 끊는다. 그 검사가 CSRF 방어로
 * 이미 작동하므로 {@code None}으로 열어도 안전하다. 쿠키 범위도 {@code Path=/api/v0/auth}로 좁혀 둔다.
 */
@Component
public class RefreshTokenCookieManager {
	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCookieManager.class);
	private static final String SAME_SITE_NONE = "None";

	private final JwtProvider jwtProvider;
	private final String cookieName;
	private final String cookiePath;
	private final boolean secure;
	private final String sameSite;

	public RefreshTokenCookieManager(
			JwtProvider jwtProvider,
			@Value("${auth.refresh-cookie.name:refresh_token}") String cookieName,
			@Value("${auth.refresh-cookie.path:/api/v0/auth}") String cookiePath,
			@Value("${auth.refresh-cookie.secure:true}") boolean secure,
			@Value("${auth.refresh-cookie.same-site:None}") String sameSite
	) {
		this.jwtProvider = jwtProvider;
		this.cookieName = cookieName;
		this.cookiePath = cookiePath;
		this.sameSite = sameSite;
		this.secure = requiresSecure(sameSite) || secure;

		if (requiresSecure(sameSite) && !secure) {
			// 브라우저는 Secure 없는 SameSite=None 쿠키를 조용히 버린다. 오류도 경고도 없이 재발급만
			// 실패하므로 설정 실수를 기동 시점에 드러낸다. 값은 위에서 Secure로 올려 두었다.
			log.warn("auth.refresh-cookie.same-site=None에는 Secure가 필수입니다. "
					+ "auth.refresh-cookie.secure=false를 무시하고 Secure를 켭니다. "
					+ "http로 띄우는 로컬 백엔드에서 테스트하려면 same-site=Lax로 두세요.");
		}
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

	/**
	 * 지우는 쿠키도 <b>굽던 것과 같은 속성</b>이어야 한다. 브라우저는 name·path·domain이 같아도
	 * SameSite·Secure가 다르면 다른 쿠키로 보고 남겨 두기 때문에, 여기가 어긋나면 로그아웃해도
	 * 쿠키가 살아남는다.
	 */
	public ResponseCookie clear() {
		return ResponseCookie.from(cookieName, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite(sameSite)
				.path(cookiePath)
				.maxAge(Duration.ZERO)
				.build();
	}

	private static boolean requiresSecure(String sameSite) {
		return sameSite != null && SAME_SITE_NONE.equalsIgnoreCase(sameSite.trim());
	}
}
