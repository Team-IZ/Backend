package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
		@Schema(description = "로그인 계정 이메일", example = "manager@example.com")
		@NotBlank @Email String email,
		@Schema(description = "로그인 계정 비밀번호", example = "Password1!")
		@NotBlank String password
) {
}
