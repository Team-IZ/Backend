package com.bigproject.backend.global.exception;

import com.bigproject.backend.domain.member.application.InvitationConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
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
