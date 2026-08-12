package com.bigproject.backend.global.security;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

public enum ManagerViewAccessErrorCode implements ApiErrorCode {
	MANAGER_VIEWER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."),
	MANAGER_VIEWER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활성 매니저만 조회할 수 있습니다."),
	MANAGER_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "매니저 권한이 필요합니다."),
	MANAGER_SCOPE_NOT_FOUND(HttpStatus.NOT_FOUND, "담당 범위에서 대상을 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ManagerViewAccessErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}
}
