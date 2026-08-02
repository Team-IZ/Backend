package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.domain.OrganizationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link OrganizationException}만 처리하는 어드바이스. global의 {@code GlobalExceptionHandler}는 손대지 않는다.
 *
 * <p>이 도메인 밖에서는 {@link OrganizationException}을 던지지 않으므로 다른 도메인의 오류 응답 형태는 그대로다.
 * 우선순위를 높여, 나중에 global 쪽에 더 넓은 핸들러가 추가되더라도 이쪽이 먼저 매칭되게 한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class OrganizationExceptionHandler {

	@ExceptionHandler(OrganizationException.class)
	public ResponseEntity<OrganizationErrorResponse> handleOrganizationException(OrganizationException exception) {
		// 5xx는 서버 결함이라 스택을 남기고, 4xx는 정상적인 사용자 흐름이라 한 줄만 남긴다.
		if (exception.errorCode().status().is5xxServerError()) {
			log.error("organization 도메인 오류: code={}", exception.errorCode(), exception);
		} else {
			log.info("organization 도메인 오류: code={}, message={}", exception.errorCode(), exception.getMessage());
		}

		return ResponseEntity
				.status(exception.errorCode().status())
				.body(OrganizationErrorResponse.of(exception.errorCode(), exception.getMessage()));
	}
}
