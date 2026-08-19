package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.domain.SessionException;
import jakarta.servlet.http.HttpServletRequest;
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
	public ResponseEntity<SessionErrorResponse> handleSessionException(
			SessionException exception, HttpServletRequest request) {
		// 5xx는 서버 결함이라 스택을 남기고, 4xx는 정상적인 사용자 흐름이라 한 줄만 남긴다.
		// method/path/origin/userAgent는 2026-08-18 GRADING_FAILED 반복 관측의 호출자를 특정할
		// 근거가 없어 추가했다 — ApiExceptionHandler와 동일한 이유(재발 시 즉시 좁히기 위함).
		if (exception.getErrorCode().status().is5xxServerError()) {
			log.error("session 도메인 오류: code={}, method={}, path={}, origin={}, userAgent={}",
					exception.getErrorCode(), request.getMethod(), request.getRequestURI(),
					request.getHeader("Origin"), request.getHeader("User-Agent"), exception);
		} else {
			log.info("session 도메인 오류: code={}, message={}, method={}, path={}, origin={}, userAgent={}",
					exception.getErrorCode(), exception.getMessage(), request.getMethod(), request.getRequestURI(),
					request.getHeader("Origin"), request.getHeader("User-Agent"));
		}

		return ResponseEntity
				.status(exception.getErrorCode().status())
				.body(SessionErrorResponse.of(exception.getErrorCode(), exception.getMessage()));
	}
}
