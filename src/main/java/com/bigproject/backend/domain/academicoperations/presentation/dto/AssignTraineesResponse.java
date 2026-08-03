package com.bigproject.backend.domain.academicoperations.presentation.dto;

import java.util.List;
import java.util.UUID;

public record AssignTraineesResponse(
		UUID classroomId,
		List<UUID> assignedTraineeIds,
		int assignedCount
) {
}
