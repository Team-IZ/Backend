package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LoginClientValidator {
	private final Set<String> allowedOrigins;

	public LoginClientValidator(
			@Value("${auth.login.allowed-origins:http://localhost:5173}") String allowedOrigins
	) {
		this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.map(this::normalizeOrigin)
				.collect(Collectors.toUnmodifiableSet());
	}

	public void validateOrigin(String origin) {
		if (origin == null || origin.isBlank() || !allowedOrigins.contains(normalizeOrigin(origin))) {
			throw forbidden("허용되지 않은 클라이언트에서 요청했습니다.");
		}
	}

	private String normalizeOrigin(String value) {
		try {
			URI uri = URI.create(value.trim());
			if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
					|| uri.getQuery() != null || uri.getFragment() != null
					|| (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
				throw forbidden("클라이언트 Origin 형식이 올바르지 않습니다.");
			}
			String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
			return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + port;
		} catch (IllegalArgumentException exception) {
			throw forbidden("클라이언트 Origin 형식이 올바르지 않습니다.");
		}
	}

	private ApiException forbidden(String message) {
		return new ApiException(AuthErrorCode.LOGIN_ORIGIN_NOT_ALLOWED, message);
	}
}
