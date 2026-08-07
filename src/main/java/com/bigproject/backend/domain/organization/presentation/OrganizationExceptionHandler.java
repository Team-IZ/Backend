package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.domain.OrganizationException;
import com.bigproject.backend.global.exception.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link OrganizationException}만 처리하는 어드바이스.
 *
 * <p>응답 본문은 global의 {@link ErrorResponse}다 — 도메인 전용 오류 DTO를 두면 생성된 프론트 타입에서
 * 오류가 도메인 수만큼 다른 타입이 되어 케이스 분기를 도메인마다 다시 짜야 한다.
 * 이 어드바이스가 하는 일은 <b>도메인 에러 코드를 {@code code}에 싣는 것</b> 하나다.
 *
 * <p>우선순위를 높여, 나중에 global 쪽에 더 넓은 핸들러가 추가되더라도 이쪽이 먼저 매칭되게 한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class OrganizationExceptionHandler {

	@ExceptionHandler(OrganizationException.class)
	public ResponseEntity<ErrorResponse> handleOrganizationException(OrganizationException exception) {
		// 5xx는 서버 결함이라 스택을 남기고, 4xx는 정상적인 사용자 흐름이라 한 줄만 남긴다.
		if (exception.errorCode().status().is5xxServerError()) {
			log.error("organization 도메인 오류: code={}", exception.errorCode(), exception);
		} else {
			log.info("organization 도메인 오류: code={}, message={}", exception.errorCode(), exception.getMessage());
		}

		String message = exception.getMessage() == null || exception.getMessage().isBlank()
				? exception.errorCode().defaultMessage()
				: exception.getMessage();

		return ResponseEntity
				.status(exception.errorCode().status())
				.body(ErrorResponse.of(exception.errorCode().status(), exception.errorCode().name(), message));
	}
}
