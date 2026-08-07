package com.bigproject.backend.global.exception;

import com.bigproject.backend.domain.member.application.InvitationConflictException;
import com.bigproject.backend.domain.auth.application.PasswordResetException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
	@ExceptionHandler(PasswordResetException.class)
	public ResponseEntity<ErrorResponse> handlePasswordReset(PasswordResetException exception) {
		return ResponseEntity.status(exception.status()).body(ErrorResponse.of(
				exception.status().value(),
				exception.code(),
				exception.getMessage()
		));
	}

	@ExceptionHandler(InvitationConflictException.class)
	public ResponseEntity<ErrorResponse> handleInvitationConflict(InvitationConflictException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
				HttpStatus.CONFLICT.value(),
				HttpStatus.CONFLICT.getReasonPhrase(),
				exception.getMessage()
		));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
			MethodArgumentNotValidException exception,
			HttpServletRequest request
	) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				"Validation Failed",
				"요청 값이 올바르지 않습니다."
		);
		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(
				HttpStatus.BAD_REQUEST.value(),
				"Validation Failed",
				"요청 값이 올바르지 않습니다."
		));
	}

	/**
	 * DB 제약 위반(CHECK·NOT NULL·UNIQUE 등).
	 *
	 * <p>잡지 않으면 Spring 기본 처리로 넘어가 <b>실행 SQL과 실패한 행 전체가 응답에 실린다.</b>
	 * 실제로 초대 취소 실패 시 사용자 이메일·초대 토큰 해시·org_id가 그대로 노출된 적이 있다.
	 *
	 * <p>원인은 <b>서버 로그에만</b> 남기고 클라이언트에는 상태와 짧은 문구만 준다.
	 * 어떤 제약이 왜 깨졌는지는 외부에 알려 줄 정보가 아니다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
			DataIntegrityViolationException exception,
			HttpServletRequest request
	) {
		log.error("DB 제약 위반: method={}, path={}", request.getMethod(), request.getRequestURI(), exception);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
				HttpStatus.CONFLICT.value(),
				"DATA_INTEGRITY_VIOLATION",
				"요청을 처리할 수 없습니다. 데이터 제약 조건에 맞지 않습니다."
		));
	}

	/*
	 * @ExceptionHandler(Exception.class) 같은 catch-all은 두지 않는다.
	 *
	 * 스프링이 상태 코드를 담아 던지는 자체 예외(NoResourceFoundException 404,
	 * HttpRequestMethodNotSupportedException 405 등)까지 가로채 전부 500으로 만들어 버린다.
	 * 실제로 넣었다가 "없는 경로 → 404" 가 500이 되는 회귀가 났다.
	 *
	 * 처리되지 않은 예외의 내부 정보 노출은 application.yaml 의
	 * server.error.include-stacktrace=never 로 막는다.
	 */

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleResponseStatus(
			ResponseStatusException exception,
			HttpServletRequest request
	) {
		int status = exception.getStatusCode().value();
		ErrorResponse response = ErrorResponse.of(
				status,
				exception.getStatusCode().toString(),
				exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason()
		);
		return ResponseEntity.status(status).body(response);
	}
}
