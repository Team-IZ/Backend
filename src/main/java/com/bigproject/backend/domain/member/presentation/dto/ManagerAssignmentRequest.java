package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ManagerAssignmentRequest(
		@Schema(description = "담당 기수 ID", type = "string", format = "uuid")
		@NotNull UUID cohortId,
		@ArraySchema(
				arraySchema = @Schema(description = "담당 반 ID 목록; 기수 전체 담당이면 비웁니다."),
				schema = @Schema(type = "string", format = "uuid")
		)
		List<@NotNull UUID> classroomIds
) {
	public ManagerAssignmentRequest {
		classroomIds = classroomIds == null ? List.of() : List.copyOf(classroomIds);
	}
}
