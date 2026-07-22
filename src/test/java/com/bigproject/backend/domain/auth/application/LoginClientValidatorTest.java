package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginClientValidatorTest {
	private final LoginClientValidator validator = new LoginClientValidator(
			"http://localhost:5173,https://team-iz.github.io",
			"/superadmin/login",
			"/manager/login"
	);

	@Test
	void acceptsSuperAdminOnlyOnSuperAdminLoginPath() {
		validator.validate("http://localhost:5173", "/superadmin/login", Role.SUPER_ADMIN);

		assertThatThrownBy(() ->
				validator.validate("http://localhost:5173", "/manager/login", Role.SUPER_ADMIN)
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("역할과 로그인 진입 경로");
	}

	@Test
	void acceptsLeadManagerAndManagerOnManagerLoginPath() {
		validator.validate("http://localhost:5173", "/manager/login", Role.LEAD_MANAGER);
		validator.validate("http://localhost:5173", "/manager/login", Role.MANAGER);
	}

	@Test
	void rejectsUnknownOriginAndTraineeLogin() {
		assertThatThrownBy(() ->
				validator.validate("http://localhost:5174", "/manager/login", Role.MANAGER)
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("허용되지 않은 클라이언트");

		assertThatThrownBy(() ->
				validator.validate("http://localhost:5173", "/manager/login", Role.TRAINEE)
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("역할과 로그인 진입 경로");
	}
}
