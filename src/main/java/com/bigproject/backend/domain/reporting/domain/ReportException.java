package com.bigproject.backend.domain.reporting.domain;

/**
 * Reporting 도메인이 에러 코드를 실어 보내기 위한 예외.
 *
 * <p>{@code ResponseStatusException}을 상속하지 않는다 — 상속하면 global의
 * {@code GlobalExceptionHandler}와 이 도메인 어드바이스가 둘 다 매칭되어 순서에 의존하게 된다
 * ({@code OrganizationException}과 같은 이유).
 */
public class ReportException extends RuntimeException {

	private final ReportErrorCode errorCode;

	public ReportException(ReportErrorCode errorCode) {
		this(errorCode, errorCode.defaultMessage(), null);
	}

	public ReportException(ReportErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public ReportException(ReportErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public ReportErrorCode errorCode() {
		return errorCode;
	}
}
