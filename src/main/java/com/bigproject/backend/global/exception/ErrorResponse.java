package com.bigproject.backend.global.exception;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
		Instant timestamp,
		int status,
		String error,
		String message
) {
	public static ErrorResponse of(int status, String error, String message) {
		return new ErrorResponse(Instant.now(), status, error, message);
	}
}
