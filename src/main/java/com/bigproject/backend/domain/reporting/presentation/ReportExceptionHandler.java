package com.bigproject.backend.domain.reporting.presentation;

import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.global.exception.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link ReportException}만 처리하는 어드바이스. 응답 본문은 global의 {@link ErrorResponse}다
 * ({@code OrganizationExceptionHandler}와 같은 구조 — 도메인마다 오류 모양이 갈리지 않게 한다).
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ReportExceptionHandler {

	@ExceptionHandler(ReportException.class)
	public ResponseEntity<ErrorResponse> handleReportException(ReportException exception) {
		// 5xx는 서버 결함이라 스택을 남기고, 4xx는 정상적인 사용자 흐름이라 한 줄만 남긴다.
		if (exception.errorCode().status().is5xxServerError()) {
			log.error("reporting 도메인 오류: code={}", exception.errorCode(), exception);
		} else {
			log.info("reporting 도메인 오류: code={}, message={}", exception.errorCode(), exception.getMessage());
		}

		String message = exception.getMessage() == null || exception.getMessage().isBlank()
				? exception.errorCode().defaultMessage()
				: exception.getMessage();

		return ResponseEntity
				.status(exception.errorCode().status())
				.body(ErrorResponse.of(exception.errorCode().status(), exception.errorCode().name(), message));
	}
}
