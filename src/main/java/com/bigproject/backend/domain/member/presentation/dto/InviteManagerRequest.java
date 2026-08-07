package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InviteManagerRequest(
		@Schema(description = "초대 대상 이메일", example = "manager@example.com")
		@NotBlank @Email @Size(max = 320) String email,
		@Schema(
				description = """
						담당할 기수 ID. 매니저 초대에서는 필수입니다. \
						반 배정은 초대 시점에 하지 않으며, 가입 후 반 편성 화면에서 따로 배정합니다.""",
				type = "string",
				example = "UUID"
		)
		UUID cohortId
) {
}
