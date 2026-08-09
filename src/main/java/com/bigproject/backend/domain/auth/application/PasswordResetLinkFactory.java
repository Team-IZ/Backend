package com.bigproject.backend.domain.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PasswordResetLinkFactory {
	private final String baseUrl;

	public PasswordResetLinkFactory(
			@Value("${password-reset.base-url:http://localhost:5173}") String baseUrl
	) {
		this.baseUrl = baseUrl;
	}

	public String create(String rawToken) {
		return UriComponentsBuilder.fromUriString(baseUrl)
				.pathSegment("password-reset", "confirm")
				.queryParam("token", rawToken)
				.build()
				.encode()
				.toUriString();
	}
}
