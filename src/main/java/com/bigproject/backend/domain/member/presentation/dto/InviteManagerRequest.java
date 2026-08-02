package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InviteManagerRequest(
		@Schema(description = "초대 대상 이메일. 대상 역할은 호출자가 SUPER_ADMIN이면 OPERATOR, OPERATOR이면 MANAGER로 서버가 결정합니다.", example = "manager@example.com")
		@NotBlank @Email @Size(max = 320) String email,
		@Schema(
				description = "OPERATOR가 MANAGER를 초대할 때 필수인 대상 기수 ID. SUPER_ADMIN의 OPERATOR 초대에서는 생략해야 합니다.",
				type = "string",
				example = "UUID"
		)
		UUID cohortId,
		@Schema(
				description = "MANAGER 초대의 선택 대상 반 ID. 지정하면 cohortId 소속 반이어야 하며, 생략하면 가입 후 미배정 상태가 됩니다. OPERATOR 초대에서는 생략해야 합니다.",
				type = "string",
				example = "UUID"
		)
		UUID targetClassId
) {
}
