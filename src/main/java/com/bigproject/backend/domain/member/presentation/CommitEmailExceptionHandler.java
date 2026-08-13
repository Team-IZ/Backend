package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.CommitEmailErrorCode;
import com.bigproject.backend.domain.member.application.CommitEmailException;
import com.bigproject.backend.global.exception.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 커밋 이메일 API의 오류만 처리한다. {@code assignableTypes}로 범위를 좁혀,
 * 여기서 Bean Validation 실패를 {@code INVALID_EMAIL_FORMAT}으로 바꿔도
 * 다른 컨트롤러의 {@code Validation Failed} 응답 형태는 그대로 유지된다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CommitEmailController.class)
public class CommitEmailExceptionHandler {

	@ExceptionHandler(CommitEmailException.class)
	public ResponseEntity<ErrorResponse> handleCommitEmail(CommitEmailException exception) {
		return toResponse(exception.errorCode(), exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		CommitEmailErrorCode errorCode = CommitEmailErrorCode.INVALID_EMAIL_FORMAT;
		return toResponse(errorCode, errorCode.defaultMessage());
	}

	private ResponseEntity<ErrorResponse> toResponse(CommitEmailErrorCode errorCode, String message) {
		return ResponseEntity.status(errorCode.status()).body(ErrorResponse.of(
				errorCode.status().value(),
				errorCode.name(),
				message
		));
	}
}
