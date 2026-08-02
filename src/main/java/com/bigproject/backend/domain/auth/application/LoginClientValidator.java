package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.member.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LoginClientValidator {
	private final Set<String> allowedOrigins;
	private final String superAdminLoginPath;
	private final String managerLoginPath;

	public LoginClientValidator(
			@Value("${auth.login.allowed-origins:http://localhost:5173}") String allowedOrigins,
			@Value("${auth.login.super-admin-path:/superadmin/login}") String superAdminLoginPath,
			@Value("${auth.login.manager-path:/manager/login}") String managerLoginPath
	) {
		this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.map(this::normalizeOrigin)
				.collect(Collectors.toUnmodifiableSet());
		this.superAdminLoginPath = normalizePath(superAdminLoginPath);
		this.managerLoginPath = normalizePath(managerLoginPath);
	}

	public void validate(String origin, String loginEntryPath, Role role) {
		validateOrigin(origin);
		String normalizedPath = normalizePath(loginEntryPath);

		boolean matches = switch (role) {
			case SUPER_ADMIN -> superAdminLoginPath.equals(normalizedPath);
			case OPERATOR, MANAGER -> managerLoginPath.equals(normalizedPath);
			case TRAINEE -> false;
		};

		if (!matches) {
			throw forbidden("계정 역할과 로그인 진입 경로가 일치하지 않습니다.");
		}
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

	private String normalizePath(String value) {
		if (value == null || value.isBlank()) {
			throw forbidden("로그인 진입 경로가 필요합니다.");
		}
		try {
			URI uri = URI.create(value.trim());
			if (uri.isAbsolute() || uri.getHost() != null || uri.getQuery() != null || uri.getFragment() != null) {
				throw forbidden("로그인 진입 경로 형식이 올바르지 않습니다.");
			}
			String path = uri.getPath();
			if (path == null || !path.startsWith("/") || path.startsWith("//")) {
				throw forbidden("로그인 진입 경로 형식이 올바르지 않습니다.");
			}
			return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		} catch (IllegalArgumentException exception) {
			throw forbidden("로그인 진입 경로 형식이 올바르지 않습니다.");
		}
	}

	private ResponseStatusException forbidden(String message) {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
	}
}
