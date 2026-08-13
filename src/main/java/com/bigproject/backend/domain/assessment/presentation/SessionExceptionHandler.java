package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.domain.SessionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** {@link SessionException}만 처리하는 어드바이스. global의 {@code GlobalExceptionHandler}는 손대지 않는다. */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SessionExceptionHandler {

	@ExceptionHandler(SessionException.class)
	public ResponseEntity<SessionErrorResponse> handleSessionException(SessionException exception) {
		// 5xx는 서버 결함이라 스택을 남기고, 4xx는 정상적인 사용자 흐름이라 한 줄만 남긴다.
		if (exception.getErrorCode().status().is5xxServerError()) {
			log.error("session 도메인 오류: code={}", exception.getErrorCode(), exception);
		} else {
			log.info("session 도메인 오류: code={}, message={}", exception.getErrorCode(), exception.getMessage());
		}

		return ResponseEntity
				.status(exception.getErrorCode().status())
				.body(SessionErrorResponse.of(exception.getErrorCode(), exception.getMessage()));
	}
}
