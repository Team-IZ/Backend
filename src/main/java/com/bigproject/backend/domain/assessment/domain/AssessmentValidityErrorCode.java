package com.bigproject.backend.domain.assessment.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

public enum AssessmentValidityErrorCode implements ApiErrorCode {
	ASSESSMENT_ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "담당 범위에서 수행을 찾을 수 없습니다."),
	VALIDITY_DECISION_INVALID(HttpStatus.BAD_REQUEST, "무효 확인 결정과 사유 조합이 올바르지 않습니다."),
	VALIDITY_REVIEW_NOT_ACTIONABLE(HttpStatus.CONFLICT, "현재 무효 확인 상태에서는 처리할 수 없습니다."),
	VALIDITY_ROW_VERSION_CONFLICT(HttpStatus.CONFLICT, "수행 상태가 변경되었습니다. 다시 조회해 주세요.");

	private final HttpStatus status;
	private final String defaultMessage;

	AssessmentValidityErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override public HttpStatus status() { return status; }
	@Override public String defaultMessage() { return defaultMessage; }
}
