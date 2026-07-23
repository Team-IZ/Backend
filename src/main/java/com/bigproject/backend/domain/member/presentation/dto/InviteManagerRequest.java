package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record InviteManagerRequest(
		@Schema(description = "초대할 매니저 이메일", example = "manager@example.com")
		@NotBlank @Email @Size(max = 320) String email,
		@NotNull
		@Schema(description = "초대할 매니저 역할", example = "MANAGER")
		ManagerInvitationRole role,
		@Schema(
				description = "일반 매니저의 필수 담당 기수 ID; 총괄 매니저는 생략",
				type = "string",
				example = "UUID"
		)
		UUID cohortId,
		@ArraySchema(
				arraySchema = @Schema(description = "일반 매니저의 담당 반 ID 목록; 기수 전체 담당이면 비웁니다."),
				schema = @Schema(type = "string", format = "uuid")
		)
		List<@NotNull UUID> classroomIds
) {
	public InviteManagerRequest {
		classroomIds = classroomIds == null ? List.of() : List.copyOf(classroomIds);
	}
}
