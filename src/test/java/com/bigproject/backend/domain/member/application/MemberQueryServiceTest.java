package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberQueryRepository;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.domain.SortDirection;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberQueryServiceTest {
	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final MemberQueryRepository memberQueryRepository = mock(MemberQueryRepository.class);
	private final MemberQueryService service = new MemberQueryService(authUserRepository, memberQueryRepository);

	@Test
	void requiresOrganizationIdForSuperAdmin() {
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null, "admin@example.com")));

		assertThatThrownBy(() -> service.findManagers(
				null,
				null,
				null,
				null,
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC,
				"admin@example.com"
		)).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		verify(memberQueryRepository, never()).findManagers(any());
	}

	@Test
	void rejectsTraineeRoleAsManagerDirectoryFilter() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.LEAD_MANAGER, organizationId, "lead@example.com")));

		assertThatThrownBy(() -> service.findManagers(
				null,
				Role.TRAINEE,
				null,
				null,
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC,
				"lead@example.com"
		)).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		verify(memberQueryRepository, never()).findManagers(any());
	}

	@Test
	void leadManagerReadsOwnOrganizationAndMapsPendingManagerWithAssignments() {
		UUID organizationId = UUID.randomUUID();
		UUID managerId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		UUID assignmentId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.LEAD_MANAGER, organizationId, "lead@example.com")));
		when(memberQueryRepository.existsOrganization(organizationId)).thenReturn(true);
		when(memberQueryRepository.findManagers(any())).thenReturn(new MemberQueryRepository.Page<>(List.of(
				new MemberQueryRepository.ManagerRow(
						managerId,
						"가입 대기",
						"manager@example.com",
						Role.MANAGER,
						"PENDING",
						false,
						organizationId,
						null
				)
		), 1));
		when(memberQueryRepository.findManagerAssignments(List.of(managerId))).thenReturn(List.of(
				new MemberQueryRepository.ManagerAssignmentRow(
						assignmentId,
						managerId,
						"COHORT",
						cohortId,
						"7기",
						null,
						null,
						Instant.parse("2026-07-01T00:00:00Z"),
						null,
						"ACTIVE"
				)
		));

		var response = service.findManagers(
				null,
				Role.MANAGER,
				AccountStatus.INVITED,
				" Manager@Example.com ",
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC,
				"lead@example.com"
		);

		assertThat(response.totalElements()).isEqualTo(1);
		assertThat(response.totalPages()).isEqualTo(1);
		assertThat(response.content()).singleElement().satisfies(manager -> {
			assertThat(manager.status()).isEqualTo(AccountStatus.INVITED);
			assertThat(manager.assignments()).singleElement().satisfies(assignment -> {
				assertThat(assignment.assignmentId()).isEqualTo(assignmentId);
				assertThat(assignment.cohortName()).isEqualTo("7기");
			});
		});
	}

	@Test
	void managerReadsWholeTraineeRosterInOwnOrganization() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		UUID traineeId = UUID.randomUUID();
		UUID classroomId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("manager@example.com"))
				.thenReturn(Optional.of(actor(Role.MANAGER, organizationId, "manager@example.com")));
		when(memberQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new MemberQueryRepository.CohortScope(cohortId, organizationId)));
		when(memberQueryRepository.findTrainees(any())).thenReturn(new MemberQueryRepository.Page<>(List.of(
				new MemberQueryRepository.TraineeRow(
						membershipId,
						traineeId,
						"교육생",
						"trainee@example.com",
						"ACTIVE",
						false,
						"ACTIVE",
						null
				)
		), 1));
		when(memberQueryRepository.findCurrentClassrooms(List.of(membershipId))).thenReturn(List.of(
				new MemberQueryRepository.CurrentClassroomRow(membershipId, classroomId, "A반")
		));

		var response = service.findTrainees(cohortId, null, null, null, 0, 20, "manager@example.com");

		assertThat(response.content()).singleElement().satisfies(trainee -> {
			assertThat(trainee.memberId()).isEqualTo(traineeId);
			assertThat(trainee.classroomId()).isEqualTo(classroomId);
			assertThat(trainee.classroomName()).isEqualTo("A반");
		});
		verify(memberQueryRepository).findTrainees(any());
	}

	@Test
	void rejectsTraineeRosterFromAnotherOrganization() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("manager@example.com"))
				.thenReturn(Optional.of(actor(Role.MANAGER, organizationId, "manager@example.com")));
		when(memberQueryRepository.findCohortScope(cohortId)).thenReturn(Optional.of(
				new MemberQueryRepository.CohortScope(cohortId, UUID.randomUUID())
		));

		assertThatThrownBy(() -> service.findTrainees(
				cohortId,
				null,
				null,
				null,
				0,
				20,
				"manager@example.com"
		)).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(memberQueryRepository, never()).findTrainees(any());
	}

	@Test
	void rejectsClassroomOutsideSelectedCohort() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		UUID classroomId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.LEAD_MANAGER, organizationId, "lead@example.com")));
		when(memberQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new MemberQueryRepository.CohortScope(cohortId, organizationId)));
		when(memberQueryRepository.classroomBelongsToCohort(classroomId, cohortId, organizationId)).thenReturn(false);

		assertThatThrownBy(() -> service.findTrainees(
				cohortId,
				classroomId,
				null,
				null,
				0,
				20,
				"lead@example.com"
		)).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	private AuthUser actor(Role role, UUID organizationId, String email) {
		return new AuthUser(
				UUID.randomUUID(),
				organizationId,
				email,
				"Actor",
				"hash",
				"ACTIVE",
				true,
				null,
				role,
				organizationId == null ? null : "ACTIVE"
		);
	}
}
