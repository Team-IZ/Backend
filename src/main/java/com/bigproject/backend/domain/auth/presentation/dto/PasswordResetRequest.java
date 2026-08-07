package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
		@Schema(description = "비밀번호 재설정 또는 계정 활성화 안내를 받을 이메일", example = "user@example.com")
		@NotBlank @Size(max = 320) @Email String email
) {
}
