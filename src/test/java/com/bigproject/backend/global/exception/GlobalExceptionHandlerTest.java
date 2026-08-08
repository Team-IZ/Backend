package com.bigproject.backend.global.exception;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

	@Test
	void 도메인_에러_코드가_응답의_code로_그대로_나간다() {
		ApiExceptionHandler handler = new ApiExceptionHandler();

		var response = handler.handleApiException(new ApiException(AuthErrorCode.RESET_TOKEN_EXPIRED));

		// 상태는 코드가 들고 있다 — 호출부마다 다른 상태로 나가는 일을 막기 위해서다.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("RESET_TOKEN_EXPIRED");
		assertThat(response.getBody().message()).isEqualTo("비밀번호 재설정 링크가 만료되었습니다.");
	}

	@Test
	void 일시_차단은_남은_시간을_본문과_헤더에_함께_싣는다() {
		ApiExceptionHandler handler = new ApiExceptionHandler();

		var response = handler.handleApiException(
				new ApiException(AuthErrorCode.LOGIN_TEMPORARILY_BLOCKED, 300L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody()).isNotNull();
		// 화면 카운트다운은 본문을, 공통 재시도 처리는 헤더를 읽는다.
		assertThat(response.getBody().retryAfter()).isEqualTo(300L);
		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
	}

	@Test
	void 차단이_아니면_retryAfter_키_자체가_없다() {
		ApiExceptionHandler handler = new ApiExceptionHandler();

		var response = handler.handleApiException(new ApiException(AuthErrorCode.LOGIN_INVALID));

		// NON_NULL이라 null이면 직렬화에서 키가 빠진다. 0이나 -1을 넣으면 화면이 그걸 대기 시간으로 읽는다.
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().retryAfter()).isNull();
		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
	}

	@Test
	void 안정_코드가_없는_예외는_HTTP_상태_이름을_코드로_쓴다() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v0/auth/login");

		var response = handler.handleResponseStatus(
				new ResponseStatusException(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다"),
				request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().status()).isEqualTo(400);
		assertThat(response.getBody().code()).isEqualTo("BAD_REQUEST");
	}
}
