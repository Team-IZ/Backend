package com.bigproject.backend.domain.member.application;

public class CommitEmailException extends RuntimeException {
	private final CommitEmailErrorCode errorCode;

	public CommitEmailException(CommitEmailErrorCode errorCode) {
		super(errorCode.defaultMessage());
		this.errorCode = errorCode;
	}

	public CommitEmailException(CommitEmailErrorCode errorCode, Throwable cause) {
		super(errorCode.defaultMessage(), cause);
		this.errorCode = errorCode;
	}

	public CommitEmailErrorCode errorCode() {
		return errorCode;
	}
}
