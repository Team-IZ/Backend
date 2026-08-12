package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "담당 매니저. 반에 활성 배정이 없으면 객체 자체가 null이다.")
public record ManagerResponse(
		UUID userId,
		@Schema(example = "김매니저") String name
) {
}
