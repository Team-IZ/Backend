package com.bigproject.backend.domain.auth.presentation.dto;

public record RefreshTokenResponse(
		String accessToken,
		long accessTokenExpiresIn
) {
}
