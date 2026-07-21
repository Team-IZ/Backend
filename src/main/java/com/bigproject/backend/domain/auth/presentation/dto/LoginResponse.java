package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;

public record LoginResponse(
		Long memberId,
		String email,
		String name,
		Role role,
		Long organizationId,
		String accessToken,
		String refreshToken,
		long accessTokenExpiresIn
) {
}
