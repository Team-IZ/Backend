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
		@Schema(description = "클라이언트 내부 이동 경로", example = "/cohorts/12%EA%B8%B0") String redirectPath,
		String accessToken,
		long accessTokenExpiresIn
) {
}
