package com.bigproject.backend.domain.classroom.presentation.dto;

import java.util.List;
import java.util.UUID;

public record ClassroomResponse(
		UUID classroomId,
		UUID cohortId,
		String name,
		List<Manager> managers,
		int traineeCount,
		boolean managerAssignmentRequired
) {
	public record Manager(UUID memberId, String name) {
	}
}
