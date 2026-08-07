package com.bigproject.backend.domain.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "유효한 초대 토큰으로 확인한 가입/활성화 대상")
public record InvitationResolveResponse(
		@Schema(description = "후속 가입 또는 활성화 요청에 전달할 초대 대상 사용자 ID", type = "string", example = "UUID")
		@JsonProperty("user_id") UUID userId,
		@Schema(description = "초대 원장과 토큰에서 확인한 읽기 전용 이메일", example = "user@example.com")
		String email,
		@Schema(
				description = "초대 대상자의 역할. 표시할 동의 항목(GET /consents?role=)과 후속 활성화 API를 이 값으로 고른다",
				allowableValues = {"SUPER_ADMIN", "OPERATOR", "MANAGER", "TRAINEE"},
				example = "MANAGER"
		)
		Role role
) {
}
