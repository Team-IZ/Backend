package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;

/**
 * Reporting 도메인 오류 응답. 프론트가 {@code code}로 분기한다 — 문구는 화면이 정한다.
 * organization 도메인의 {@code OrganizationErrorResponse}와 같은 모양이다.
 */
public record ReportErrorResponse(String code, String message) {

	public static ReportErrorResponse of(ReportErrorCode errorCode, String message) {
		return new ReportErrorResponse(
				errorCode.name(),
				message == null || message.isBlank() ? errorCode.defaultMessage() : message
		);
	}
}
