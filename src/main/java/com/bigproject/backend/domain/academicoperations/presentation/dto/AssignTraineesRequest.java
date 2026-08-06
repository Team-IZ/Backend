package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "교육생 일괄 반 배정 요청")
public record AssignTraineesRequest(
		@Schema(description = "배정할 교육생의 사용자 ID(user_id) 목록. 1건 이상 필수. 중복은 서버가 제거한다. " +
				"그 기수 소속이 아닌 ID가 하나라도 섞여 있으면 전체가 400으로 거절된다.")
		@NotEmpty List<UUID> traineeIds,

		@Schema(description = "배정할 반 ID. 요청한 cohortId 소속이 아니면 404", example = "123e4567-e89b-12d3-a456-426614174000")
		@NotNull UUID classroomId
) {
}