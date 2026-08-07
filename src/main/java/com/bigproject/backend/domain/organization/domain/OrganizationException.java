package com.bigproject.backend.domain.organization.domain;

import com.bigproject.backend.global.exception.ApiException;

/**
 * organization/operations 도메인이 에러 코드를 실어 보내기 위한 예외.
 *
 * <p>{@link ApiException}을 상속하므로 전용 어드바이스가 필요 없다 —
 * {@code ApiExceptionHandler} 하나가 모든 도메인의 코드를 응답에 싣는다.
 * 이 타입을 남겨 두는 이유는 {@link #errorCode()}가 {@link OrganizationErrorCode}를 그대로 돌려주어
 * 호출부에서 코드 범위가 좁게 유지되기 때문이다.
 */
public class OrganizationException extends ApiException {

	public OrganizationException(OrganizationErrorCode errorCode) {
		super(errorCode);
	}

	public OrganizationException(OrganizationErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	public OrganizationException(OrganizationErrorCode errorCode, String message, Throwable cause) {
		super(errorCode, message, cause);
	}

	@Override
	public OrganizationErrorCode errorCode() {
		return (OrganizationErrorCode) super.errorCode();
	}
}
