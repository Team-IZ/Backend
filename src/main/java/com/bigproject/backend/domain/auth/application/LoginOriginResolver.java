package com.bigproject.backend.domain.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@Component
public class LoginOriginResolver {
	private final boolean swaggerOverrideEnabled;
	private final String swaggerUiOrigin;

	public LoginOriginResolver(
			@Value("${auth.login.swagger-origin-override-enabled}") boolean swaggerOverrideEnabled,
			@Value("${auth.login.swagger-ui-origin}") String swaggerUiOrigin
	) {
		this.swaggerOverrideEnabled = swaggerOverrideEnabled;
		this.swaggerUiOrigin = normalizeOrigin(swaggerUiOrigin);
	}

	public String resolve(String requestOrigin, String swaggerClientOrigin) {
		if (swaggerClientOrigin == null || swaggerClientOrigin.isBlank()) {
			return requestOrigin;
		}

		if (!swaggerOverrideEnabled) {
			throw forbidden("Swagger Origin 대체 기능이 비활성화되어 있습니다.");
		}

		if (swaggerUiOrigin.isEmpty() || !swaggerUiOrigin.equals(normalizeOrigin(requestOrigin))) {
			throw forbidden("Swagger UI 요청에서만 클라이언트 Origin을 직접 지정할 수 있습니다.");
		}

		return swaggerClientOrigin.trim();
	}

	private String normalizeOrigin(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}

		try {
			URI uri = URI.create(value.trim());
			if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
					|| uri.getQuery() != null || uri.getFragment() != null
					|| uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
				return "";
			}

			String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
			return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + port;
		} catch (IllegalArgumentException exception) {
			return "";
		}
	}

	private ResponseStatusException forbidden(String message) {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
	}
}
