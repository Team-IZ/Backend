package com.bigproject.backend.domain.academicoperations.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record AssignTraineesRequest(
		@NotEmpty List<UUID> traineeIds,
		@NotNull UUID classroomId
) {
}
