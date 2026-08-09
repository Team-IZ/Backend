package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record LoginResponse(
		@Schema(type = "string", example = "UUID") UUID memberId,
		String email,
		String name,
		Role role,
		@Schema(type = "string", example = "UUID", nullable = true) UUID organizationId,
		@Schema(
				description = "클라이언트 내부 이동 경로 제안. 백엔드가 프론트 라우트를 추측해 만든 값이라 "
						+ "**따르지 않아도 된다** — role로 라우팅해도 무방하다.",
				example = "/cohorts/12%EA%B8%B0"
		)
		String redirectPath,
		String accessToken,
		@Schema(
				description = "만료까지 남은 시간. **단위는 밀리초(ms)**다 — 1시간이면 3600000이다. "
						+ "만료 시각은 `Date.now() + accessTokenExpiresIn`이다.",
				example = "3600000"
		)
		long accessTokenExpiresIn
) {
}
