package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record LoginResponse(
		@Schema(type = "string", example = "UUID") UUID memberId,
		String email,
		String name,
		Role role,
		@Schema(type = "string", example = "UUID") UUID organizationId,
		String accessToken,
		long accessTokenExpiresIn
) {
}
