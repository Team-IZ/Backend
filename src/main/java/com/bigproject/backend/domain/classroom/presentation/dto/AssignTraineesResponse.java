package com.bigproject.backend.domain.classroom.presentation.dto;

import java.util.List;
import java.util.UUID;

public record AssignTraineesResponse(
		UUID classroomId,
		List<UUID> assignedTraineeIds,
		int assignedCount
) {
}
