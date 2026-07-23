package com.bigproject.backend.domain.member.presentation.dto;

import java.time.Instant;
import java.util.UUID;

public record ManagerAssignmentResponse(
		UUID assignmentId,
		String scope,
		UUID cohortId,
		String cohortName,
		UUID classroomId,
		String classroomName,
		Instant assignedAt,
		Instant unassignedAt,
		String status
) {
}
