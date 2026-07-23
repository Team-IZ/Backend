package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberQueryRepository {
	boolean existsOrganization(UUID organizationId);

	Optional<CohortScope> findCohortScope(UUID cohortId);

	boolean classroomBelongsToCohort(UUID classroomId, UUID cohortId, UUID organizationId);

	Page<ManagerRow> findManagers(ManagerCriteria criteria);

	List<ManagerAssignmentRow> findManagerAssignments(List<UUID> managerIds);

	Page<TraineeRow> findTrainees(TraineeCriteria criteria);

	List<CurrentClassroomRow> findCurrentClassrooms(List<UUID> cohortMemberIds);

	record ManagerCriteria(
			UUID organizationId,
			Role role,
			AccountStatus status,
			String query,
			int page,
			int size,
			MemberSortField sortBy,
			SortDirection direction
	) {
	}

	record TraineeCriteria(
			UUID cohortId,
			UUID organizationId,
			UUID classroomId,
			AccountStatus status,
			String query,
			int page,
			int size
	) {
	}

	record ManagerRow(
			UUID memberId,
			String name,
			String email,
			Role role,
			String databaseStatus,
			boolean deleted,
			UUID organizationId,
			Instant lastLoginAt
	) {
	}

	record ManagerAssignmentRow(
			UUID assignmentId,
			UUID managerId,
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

	record TraineeRow(
			UUID cohortMemberId,
			UUID memberId,
			String name,
			String email,
			String databaseStatus,
			boolean deleted,
			String membershipStatus,
			Instant leftAt
	) {
	}

	record CurrentClassroomRow(
			UUID cohortMemberId,
			UUID classroomId,
			String classroomName
	) {
	}

	record CohortScope(UUID cohortId, UUID organizationId) {
	}

	record Page<T>(List<T> content, long totalElements) {
		public Page {
			content = List.copyOf(content);
		}
	}
}
