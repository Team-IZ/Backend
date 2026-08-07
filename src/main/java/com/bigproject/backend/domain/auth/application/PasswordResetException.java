package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import org.springframework.http.HttpStatus;

public class PasswordResetException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	/** 상태와 기본 메시지를 코드가 들고 있으므로 호출부는 코드만 고르면 된다. */
	public PasswordResetException(AuthErrorCode errorCode) {
		this(errorCode, null);
	}

	public PasswordResetException(AuthErrorCode errorCode, Throwable cause) {
		this(errorCode.status(), errorCode.name(), errorCode.defaultMessage(), cause);
	}

	public PasswordResetException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public PasswordResetException(HttpStatus status, String code, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
