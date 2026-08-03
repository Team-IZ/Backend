package com.bigproject.backend.domain.auth.application;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginClientValidatorTest {
	private final LoginClientValidator validator = new LoginClientValidator(
			"http://localhost:5173,https://team-iz.github.io"
	);

	@Test
	void acceptsConfiguredOriginWithoutCheckingLoginPathOrRole() {
		validator.validateOrigin("http://localhost:5173");
		validator.validateOrigin("https://team-iz.github.io");
	}

	@Test
	void rejectsUnknownOrigin() {
		assertThatThrownBy(() ->
				validator.validateOrigin("http://localhost:5174")
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("허용되지 않은 클라이언트");
	}
}
