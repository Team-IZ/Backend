package com.bigproject.backend.domain.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TraineeActivationRequest(
		@Schema(description = "초대 토큰 해석 응답의 PENDING 교육생 사용자 ID", type = "string", format = "uuid", example = "UUID")
		@NotNull @JsonProperty("user_id") UUID userId,
		@Schema(description = "INVITE_TRAINEE 초대 링크의 현재 일회용 원문 토큰", example = "invitation-token")
		@NotBlank @Size(max = 512) String invitationToken,
		@Schema(description = "영문·숫자·특수문자를 포함한 8~64자 비밀번호", example = "Password1!")
		@NotBlank
		@Size(min = 8, max = 64)
		@Pattern(
				regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
				message = "비밀번호는 8자 이상이며 영문, 숫자, 특수문자를 포함해야 합니다."
		)
		String password,
		@Schema(description = "비밀번호와 동일하게 입력하는 확인 값", example = "Password1!")
		@NotBlank @Size(max = 64) String passwordConfirmation,
		@Schema(description = "서비스 이용약관 필수 동의 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		@AssertTrue(message = "서비스 이용약관 동의는 필수입니다.") boolean serviceTermsAgreed,
		@Schema(description = "개인정보 수집 필수 동의 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		@AssertTrue(message = "개인정보 수집 동의는 필수입니다.") boolean privacyCollectionAgreed,
		@Schema(description = "AI 분석 필수 동의 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		@AssertTrue(message = "AI 분석 동의는 필수입니다.") boolean aiAnalysisAgreed,
		@Schema(description = "기관 공유 필수 동의 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		@AssertTrue(message = "기관 공유 동의는 필수입니다.") boolean organizationSharingAgreed,
		@Schema(description = "익명 데이터 서비스 개선 선택 동의 여부. 유일한 선택 항목이라 생략하면 미동의로 처리한다.",
				example = "false")
		boolean anonymousImprovementAgreed
) {
	@AssertTrue(message = "비밀번호 확인이 일치하지 않습니다.")
	@Schema(hidden = true)
	public boolean isPasswordConfirmed() {
		return password != null && password.equals(passwordConfirmation);
	}
}
