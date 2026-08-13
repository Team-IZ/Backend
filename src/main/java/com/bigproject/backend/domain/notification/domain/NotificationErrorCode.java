package com.bigproject.backend.domain.notification.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

public enum NotificationErrorCode implements ApiErrorCode {
	REMINDER_TARGET_INVALID(HttpStatus.BAD_REQUEST, "팀 또는 교육생 중 하나만 지정해야 합니다."),
	REMINDER_REASON_INVALID(HttpStatus.BAD_REQUEST, "사용자 독촉 사유가 올바르지 않습니다."),
	REMINDER_TARGET_NOT_ELIGIBLE(HttpStatus.CONFLICT, "현재 상태에서는 독촉할 수 없습니다."),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 멱등 키가 다른 요청에 사용되었습니다."),
	INBOX_CURSOR_INVALID(HttpStatus.BAD_REQUEST, "인박스 커서가 올바르지 않습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	NotificationErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override public HttpStatus status() { return status; }
	@Override public String defaultMessage() { return defaultMessage; }
}
