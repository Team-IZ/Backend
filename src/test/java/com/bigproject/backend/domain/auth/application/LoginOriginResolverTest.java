package com.bigproject.backend.domain.auth.application;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginOriginResolverTest {
	@Test
	void usesBrowserOriginWhenSwaggerHeaderIsAbsent() {
		LoginOriginResolver resolver = new LoginOriginResolver(false, "http://localhost:8080");

		String origin = resolver.resolve("http://localhost:5173", null);

		assertThat(origin).isEqualTo("http://localhost:5173");
	}

	@Test
	void acceptsDirectOriginOnlyFromConfiguredSwaggerUi() {
		LoginOriginResolver resolver = new LoginOriginResolver(true, "http://localhost:8080");

		String origin = resolver.resolve("http://localhost:8080", " http://localhost:5173 ");

		assertThat(origin).isEqualTo("http://localhost:5173");
	}

	@Test
	void rejectsDirectOriginWhenOverrideIsDisabled() {
		LoginOriginResolver resolver = new LoginOriginResolver(false, "http://localhost:8080");

		assertThatThrownBy(() ->
				resolver.resolve("http://localhost:8080", "http://localhost:5173")
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("비활성화");
	}

	@Test
	void rejectsDirectOriginOutsideConfiguredSwaggerUi() {
		LoginOriginResolver resolver = new LoginOriginResolver(true, "http://localhost:8080");

		assertThatThrownBy(() ->
				resolver.resolve("http://localhost:5173", "https://team-iz.github.io")
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Swagger UI 요청");
	}

	@Test
	void rejectsDirectOriginWhenBrowserOriginIsMissing() {
		LoginOriginResolver resolver = new LoginOriginResolver(true, "http://localhost:8080");

		assertThatThrownBy(() ->
				resolver.resolve(null, "http://localhost:5173")
		).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Swagger UI 요청");
	}
}
