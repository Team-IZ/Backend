package com.bigproject.backend.domain.organization.domain;

/**
 * organization/operations 도메인이 목업 케이스 계약대로 <b>에러 코드</b>를 실어 보내기 위한 예외.
 *
 * <p>{@code ResponseStatusException}을 상속하지 않는다 — 상속하면 global의
 * {@code GlobalExceptionHandler.handleResponseStatus}와 이 도메인의 어드바이스가 둘 다 매칭되어
 * 어느 쪽이 잡을지 순서에 의존하게 된다. 별도 타입으로 두면 이 도메인 어드바이스만 매칭된다.
 */
public class OrganizationException extends RuntimeException {

	private final OrganizationErrorCode errorCode;

	public OrganizationException(OrganizationErrorCode errorCode) {
		this(errorCode, errorCode.defaultMessage(), null);
	}

	public OrganizationException(OrganizationErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public OrganizationException(OrganizationErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public OrganizationErrorCode errorCode() {
		return errorCode;
	}
}
