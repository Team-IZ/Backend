package com.bigproject.backend.domain.classroom.presentation.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record CreateClassroomRequest(
		@NotBlank String name,
		List<UUID> managerIds
) {
	public CreateClassroomRequest {
		managerIds = managerIds == null ? List.of() : List.copyOf(managerIds);
	}
}
