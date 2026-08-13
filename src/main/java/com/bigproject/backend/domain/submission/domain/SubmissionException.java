package com.bigproject.backend.domain.submission.domain;

/**
 * submission 도메인이 에러 코드를 실어 보내기 위한 예외.
 *
 * <p>{@code ResponseStatusException}을 상속하지 않는다 — 상속하면 global의
 * {@code GlobalExceptionHandler.handleResponseStatus}와 이 도메인의 어드바이스가 둘 다 매칭되어
 * 어느 쪽이 잡을지 순서에 의존하게 된다.
 */
public class SubmissionException extends RuntimeException {

	private final SubmissionErrorCode errorCode;

	public SubmissionException(SubmissionErrorCode errorCode) {
		this(errorCode, errorCode.defaultMessage(), null);
	}

	public SubmissionException(SubmissionErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public SubmissionException(SubmissionErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public SubmissionErrorCode errorCode() {
		return errorCode;
	}
}
