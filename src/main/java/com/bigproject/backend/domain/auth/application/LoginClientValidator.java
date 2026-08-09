package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.global.config.AllowedOriginPolicy;
import com.bigproject.backend.global.exception.ApiException;
import org.springframework.stereotype.Component;

/**
 * 로그인·재발급·로그아웃 요청의 {@code Origin}을 <b>서버가 직접</b> 검사한다.
 *
 * <p>CORS와 역할이 겹치지 않는다 — CORS는 브라우저가 응답을 넘겨주지 않는 것이고 이쪽은 서버가
 * 403으로 거절하는 것이다. 이 검사가 있어서 리프레시 쿠키를 {@code SameSite=None}으로 열어도
 * CSRF 방어가 남는다({@link com.bigproject.backend.domain.auth.presentation.RefreshTokenCookieManager}).
 *
 * <p>허용 목록 자체는 {@link AllowedOriginPolicy}가 들고 있다. CORS 설정과 같은 값을 봐야
 * "CORS는 통과했는데 403"이 생기지 않는다.
 */
@Component
public class LoginClientValidator {
	private final AllowedOriginPolicy allowedOriginPolicy;

	public LoginClientValidator(AllowedOriginPolicy allowedOriginPolicy) {
		this.allowedOriginPolicy = allowedOriginPolicy;
	}

	public void validateOrigin(String origin) {
		if (!allowedOriginPolicy.isAllowed(origin)) {
			throw new ApiException(AuthErrorCode.LOGIN_ORIGIN_NOT_ALLOWED, "허용되지 않은 클라이언트에서 요청했습니다.");
		}
	}
}
