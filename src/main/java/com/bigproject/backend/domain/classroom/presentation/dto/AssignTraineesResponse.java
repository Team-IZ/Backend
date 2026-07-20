package com.bigproject.backend.domain.classroom.presentation.dto;

import java.util.List;

public record AssignTraineesResponse(
		Long classroomId,
		List<Long> assignedTraineeIds,
		int assignedCount
) {
}
