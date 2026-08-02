package com.bigproject.backend.global.exception;

import com.bigproject.backend.domain.auth.application.PasswordResetException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
	@Test
	void preservesPasswordResetStableErrorCode() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();

		var response = handler.handlePasswordReset(new PasswordResetException(
				HttpStatus.GONE,
				"RESET_TOKEN_EXPIRED",
				"비밀번호 재설정 링크가 만료되었습니다."
		));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error()).isEqualTo("RESET_TOKEN_EXPIRED");
	}

	@Test
	void convertsLoginFailureToErrorResponse() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v0/auth/login");

		var response = handler.handleResponseStatus(
				new ResponseStatusException(HttpStatus.BAD_REQUEST, "로그인을 실패했습니다"),
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().status()).isEqualTo(400);
		assertThat(response.getBody().message()).isEqualTo("로그인을 실패했습니다");
	}
}
