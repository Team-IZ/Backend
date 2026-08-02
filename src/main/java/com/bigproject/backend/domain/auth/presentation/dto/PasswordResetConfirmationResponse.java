package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PasswordResetConfirmationResponse(
		@Schema(example = "COMPLETED") String status,
		@Schema(example = "비밀번호가 바뀌었습니다. 새 비밀번호로 다시 로그인해 주세요.") String message
) {
}
