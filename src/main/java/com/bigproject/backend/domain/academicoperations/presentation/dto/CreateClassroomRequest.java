package com.bigproject.backend.domain.academicoperations.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateClassroomRequest(
		@NotBlank String name,
		@NotNull @Min(1) Integer capacity,
		List<UUID> managerIds
) {
	public CreateClassroomRequest {
		managerIds = managerIds == null ? List.of() : List.copyOf(managerIds);
	}
}
