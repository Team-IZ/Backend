package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetValidationRequest(
		@Schema(description = "메일 링크에 담긴 원문 토큰. 서버에는 해시만 저장됩니다.", example = "password-reset-token")
		@NotBlank @Size(max = 512) String token
) {
}
