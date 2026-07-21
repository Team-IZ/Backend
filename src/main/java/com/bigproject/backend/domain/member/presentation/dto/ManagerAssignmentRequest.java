package com.bigproject.backend.domain.member.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ManagerAssignmentRequest(
		@NotNull Long cohortId,
		List<Long> classroomIds
) {
	public ManagerAssignmentRequest {
		classroomIds = classroomIds == null ? List.of() : List.copyOf(classroomIds);
	}
}
