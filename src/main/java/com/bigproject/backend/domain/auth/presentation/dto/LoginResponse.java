package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;

import java.util.UUID;

public record LoginResponse(
		UUID memberId,
		String email,
		String name,
		Role role,
		UUID organizationId,
		String accessToken,
		long accessTokenExpiresIn
) {
}
