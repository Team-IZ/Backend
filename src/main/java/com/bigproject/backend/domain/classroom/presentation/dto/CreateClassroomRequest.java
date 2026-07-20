package com.bigproject.backend.domain.classroom.presentation.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateClassroomRequest(
		@NotBlank String name,
		List<Long> managerIds
) {
	public CreateClassroomRequest {
		managerIds = managerIds == null ? List.of() : List.copyOf(managerIds);
	}
}
