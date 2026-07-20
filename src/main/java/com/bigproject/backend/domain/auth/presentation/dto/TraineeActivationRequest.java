package com.bigproject.backend.domain.auth.presentation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TraineeActivationRequest(
		@NotBlank String invitationToken,
		@NotBlank
		@Pattern(
				regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
				message = "비밀번호는 8자 이상이며 영문, 숫자, 특수문자를 포함해야 합니다."
		)
		String password,
		@NotBlank String passwordConfirmation,
		@AssertTrue boolean serviceTermsAgreed,
		@AssertTrue boolean privacyCollectionAgreed,
		@AssertTrue boolean aiAnalysisAgreed,
		@AssertTrue boolean organizationSharingAgreed,
		boolean anonymousImprovementAgreed
) {
	@AssertTrue(message = "비밀번호 확인이 일치하지 않습니다.")
	public boolean isPasswordConfirmed() {
		return password != null && password.equals(passwordConfirmation);
	}
}
