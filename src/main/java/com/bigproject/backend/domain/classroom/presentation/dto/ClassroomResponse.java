package com.bigproject.backend.domain.classroom.presentation.dto;

import com.bigproject.backend.domain.classroom.application.ClassroomService;

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
	// 매니저 이름은 app_user 조인이 필요해 member 도메인 의존이 생기므로 여기서는 채우지 않음 (memberId만 제공)
	public record Manager(UUID memberId, String name) {
	}

	public static ClassroomResponse from(ClassroomService.ClassroomView view) {
		List<Manager> managers = view.managerUserIds().stream()
				.map(managerUserId -> new Manager(managerUserId, ""))
				.toList();
		return new ClassroomResponse(
				view.classroom().getClassId(),
				view.classroom().getCohortId(),
				view.classroom().getName(),
				managers,
				Math.toIntExact(view.traineeCount()),
				managers.isEmpty()
		);
	}
}
