package com.bigproject.backend.domain.member.application;

import org.springframework.http.HttpStatus;

public enum CommitEmailErrorCode {
	INVALID_EMAIL_FORMAT(HttpStatus.BAD_REQUEST, "커밋 이메일 형식이 올바르지 않습니다."),
	COMMIT_EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "같은 기관의 다른 사용자가 이미 사용 중인 커밋 이메일입니다."),
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "인증된 사용자의 계정을 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	CommitEmailErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
