package com.bigproject.backend.domain.classroom.presentation.dto;

import java.util.List;

public record ClassroomResponse(
		Long classroomId,
		Long cohortId,
		String name,
		List<Manager> managers,
		int traineeCount,
		boolean managerAssignmentRequired
) {
	public record Manager(Long memberId, String name) {
	}
}
