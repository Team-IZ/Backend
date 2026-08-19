package com.bigproject.backend.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@link ApiException}을 {@link ErrorResponse}로 바꾸는 유일한 어드바이스.
 *
 * <p>도메인마다 있던 {@code OrganizationExceptionHandler}·{@code ReportExceptionHandler}를
 * 여기로 합쳤다. 하는 일은 <b>도메인 에러 코드를 {@code code}에 싣는 것</b> 하나뿐이라
 * 도메인별로 다를 이유가 없었고, 도메인이 늘 때마다 같은 파일이 한 벌씩 복사되고 있었다.
 *
 * <p>우선순위를 최상위로 둬서, 나중에 더 넓은 핸들러가 추가돼도 이쪽이 먼저 매칭되게 한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
		ApiErrorCode errorCode = exception.errorCode();

		// 5xx는 서버 결함이라 스택을 남기고, 4xx는 정상적인 사용자 흐름이라 한 줄만 남긴다.
		// method/path/origin/userAgent는 2026-08-18 특정 화면들에서 반복 관측된 404 폭주(예:
		// CURRICULUM_MATERIAL_NOT_FOUND)의 호출자를 특정할 근거가 없어 추가했다 — 재발 시 이
		// 필드만으로 어느 배포/클라이언트인지 바로 좁힐 수 있어야 한다.
		if (errorCode.status().is5xxServerError()) {
			log.error("도메인 오류: code={}, method={}, path={}, origin={}, userAgent={}",
					errorCode.name(), request.getMethod(), request.getRequestURI(),
					request.getHeader("Origin"), request.getHeader("User-Agent"), exception);
		} else {
			log.info("도메인 오류: code={}, message={}, method={}, path={}, origin={}, userAgent={}",
					errorCode.name(), exception.getMessage(), request.getMethod(), request.getRequestURI(),
					request.getHeader("Origin"), request.getHeader("User-Agent"));
		}

		String message = exception.getMessage() == null || exception.getMessage().isBlank()
				? errorCode.defaultMessage()
				: exception.getMessage();

		Long retryAfter = exception.retryAfterSeconds();
		if (retryAfter == null) {
			return ResponseEntity
					.status(errorCode.status())
					.body(ErrorResponse.of(errorCode.status(), errorCode.name(), message));
		}
		// 헤더와 본문에 같은 값을 싣는다 — 공통 재시도 처리는 헤더를, 카운트다운 화면은 본문을 읽는다.
		return ResponseEntity
				.status(errorCode.status())
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
				.body(ErrorResponse.of(errorCode.status(), errorCode.name(), message, retryAfter));
	}
}
