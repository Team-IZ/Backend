package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.application.AccountActivationService;
import com.bigproject.backend.domain.auth.application.AuthService;
import com.bigproject.backend.domain.auth.application.InvitationResolveService;
import com.bigproject.backend.domain.auth.application.LoginOriginResolver;
import com.bigproject.backend.domain.auth.application.PasswordResetService;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequestResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordResetControllerTest {
	@Test
	void requestEndpointReturnsAcceptedWithEnumerationSafeMessage() {
		PasswordResetService passwordResetService = mock(PasswordResetService.class);
		when(passwordResetService.request("user@example.com", "request-id"))
				.thenReturn(new PasswordResetRequestResponse(
						"입력하신 주소가 계정에 등록돼 있으면 안내 메일이 도착합니다."
				));
		AuthController controller = new AuthController(
				mock(AuthService.class),
				mock(AccountActivationService.class),
				mock(InvitationResolveService.class),
				mock(RefreshTokenCookieManager.class),
				mock(LoginOriginResolver.class),
				passwordResetService
		);

		var response = controller.requestPasswordReset(
				new PasswordResetRequest("user@example.com"),
				"request-id"
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().message()).contains("계정에 등록돼 있으면");
	}
}
