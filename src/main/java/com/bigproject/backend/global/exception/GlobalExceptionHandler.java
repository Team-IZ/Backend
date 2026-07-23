package com.bigproject.backend.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
