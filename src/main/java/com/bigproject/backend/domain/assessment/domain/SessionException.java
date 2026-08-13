package com.bigproject.backend.domain.assessment.domain;

import lombok.Getter;

/** 검증 세션 API의 도메인 예외. {@link SessionErrorCode}가 상태 코드와 문구를 함께 들고 있다. */
@Getter
public class SessionException extends RuntimeException {

	private final SessionErrorCode errorCode;

	public SessionException(SessionErrorCode errorCode) {
		super(errorCode.message());
		this.errorCode = errorCode;
	}

	public SessionException(SessionErrorCode errorCode, Throwable cause) {
		super(errorCode.message(), cause);
		this.errorCode = errorCode;
	}
}
