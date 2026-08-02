package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmationRequest(
		@Schema(description = "메일 링크에 포함된 비밀번호 재설정 원문 토큰")
		@NotBlank @Size(max = 512) String token,
		@Schema(description = "영문·숫자·특수문자를 포함한 8~64자 새 비밀번호", example = "Password1!")
		@NotBlank @Size(max = 64) String newPassword,
		@Schema(description = "새 비밀번호 확인 값", example = "Password1!")
		@NotBlank @Size(max = 64) String newPasswordConfirmation
) {
	@AssertTrue(message = "비밀번호 확인이 일치하지 않습니다.")
	@Schema(hidden = true)
	public boolean isPasswordConfirmed() {
		return newPassword != null && newPassword.equals(newPasswordConfirmation);
	}
}
