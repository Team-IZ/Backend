package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;

public record LoginResult(
		LoginResponse response,
		String refreshToken
) {
}
