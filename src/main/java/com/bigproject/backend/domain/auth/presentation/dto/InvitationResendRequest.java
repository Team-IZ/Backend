package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InvitationResendRequest(
		@Schema(description = "초대 메일을 다시 받을 이메일", example = "user@example.com")
		@NotBlank @Size(max = 320) @Email String email
) {
}
