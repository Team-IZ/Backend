package com.bigproject.backend.domain.classroom.presentation.dto;

import com.bigproject.backend.domain.classroom.domain.Classroom;

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

	// manager_assignment, class_membership 테이블이 아직 없어서 담당 매니저·인원수는 실제 값을 채울 수 없음
	public static ClassroomResponse from(Classroom classroom) {
		return new ClassroomResponse(
				classroom.getClassId(),
				classroom.getCohortId(),
				classroom.getName(),
				List.of(),
				0,
				true
		);
	}
}
