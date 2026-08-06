package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "아직 사용할 수 있는 비밀번호 재설정 토큰의 확인 결과")
public record PasswordResetValidationResponse(
		@Schema(description = "재설정 대상 이메일이며 읽기 전용으로 표시한다", example = "user@example.com")
		String email,
		@Schema(description = "이 토큰이 만료되는 시각")
		Instant expiresAt
) {
}
