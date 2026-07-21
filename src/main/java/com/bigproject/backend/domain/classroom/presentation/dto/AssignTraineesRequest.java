package com.bigproject.backend.domain.classroom.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AssignTraineesRequest(
		@NotEmpty List<Long> traineeIds,
		@NotNull Long classroomId
) {
}
