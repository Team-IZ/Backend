package com.bigproject.backend.domain.auth.application;

import org.springframework.http.HttpStatus;

public class PasswordResetException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

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
