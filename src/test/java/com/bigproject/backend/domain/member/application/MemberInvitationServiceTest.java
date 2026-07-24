package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.ManagerInvitationRole;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberInvitationServiceTest {
	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final MemberInvitationRepository invitationRepository = mock(MemberInvitationRepository.class);
	private final TransactionalInvitationDispatcher invitationDispatcher = mock(TransactionalInvitationDispatcher.class);
	private final MemberInvitationService service = new MemberInvitationService(
			authUserRepository,
			invitationRepository,
			invitationDispatcher
	);

	@Test
	void superAdminInvitesLeadManager() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("lead@example.com", ManagerInvitationRole.LEAD_MANAGER, null, List.of());
		PendingInvitation invitation = pendingInvitation(context, request.email(), request.role().toRole());
		when(authUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, actor, "request-1"))
				.thenReturn(invitation);

		var response = service.inviteManager(
				organizationId,
				request,
				"ADMIN@example.com",
				"request-1"
		);

		assertThat(response.memberId()).isEqualTo(invitation.memberId());
		assertThat(response.role()).isEqualTo(Role.LEAD_MANAGER);
		verify(invitationDispatcher).inviteManager(context, request, actor, "request-1");
	}

	@Test
	void leadManagerInvitesManagerInOwnOrganization() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.LEAD_MANAGER, organizationId);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest(
				"manager@example.com",
				ManagerInvitationRole.MANAGER,
				UUID.randomUUID(),
				List.of(UUID.randomUUID(), UUID.randomUUID())
		);
		PendingInvitation invitation = pendingInvitation(context, request.email(), request.role().toRole());
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, actor, "request-2"))
				.thenReturn(invitation);

		var response = service.inviteManager(organizationId, request, "lead@example.com", "request-2");

		assertThat(response.role()).isEqualTo(Role.MANAGER);
		verify(invitationDispatcher).inviteManager(context, request, actor, "request-2");
	}

	@Test
	void rejectsManagerRoleInvitationFromSuperAdmin() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("manager@example.com", ManagerInvitationRole.MANAGER, UUID.randomUUID(), List.of()),
				"admin@example.com",
				null
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("초대할 권한");
		verify(invitationDispatcher, never()).inviteManager(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void rejectsManagerInvitationWithoutCohort() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.LEAD_MANAGER, organizationId)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("manager@example.com", ManagerInvitationRole.MANAGER, null, List.of()),
				"lead@example.com",
				null
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("하나의 기수");
		verify(invitationDispatcher, never()).inviteManager(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void rejectsLeadManagerInvitationWithCohortAssignment() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest(
						"lead@example.com",
						ManagerInvitationRole.LEAD_MANAGER,
						UUID.randomUUID(),
						List.of(UUID.randomUUID())
				),
				"admin@example.com",
				null
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("기수·반을 배정하지 않습니다");
		verify(invitationDispatcher, never()).inviteManager(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void leadManagerDoesNotInviteDuplicateEmailsFromCsv() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.LEAD_MANAGER, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		RegisterTraineesRequest.Trainee first = new RegisterTraineesRequest.Trainee(
				"교육생",
				"trainee@example.com"
		);
		RegisterTraineesRequest.Trainee duplicate = new RegisterTraineesRequest.Trainee(
				"중복",
				" TRAINEE@example.com "
		);
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));

		var response = service.inviteTraineesFromCsv(
				cohortId,
				List.of(
						new TraineeCsvRow(2, first.name(), first.email()),
						new TraineeCsvRow(3, duplicate.name(), duplicate.email())
				),
				"lead@example.com",
				"batch-1"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		assertThat(response.registeredCount()).isZero();
		assertThat(response.invitationSentCount()).isZero();
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::row)
				.containsExactly(2, 3);
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::status)
				.containsExactly(2, 2);
		verify(invitationDispatcher, never()).inviteTrainee(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void leadManagerDirectlyInvitesMultipleTrainees() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.LEAD_MANAGER, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		RegisterTraineesRequest.Trainee first = new RegisterTraineesRequest.Trainee(
				"교육생1",
				"trainee1@example.com"
		);
		RegisterTraineesRequest.Trainee second = new RegisterTraineesRequest.Trainee(
				"교육생2",
				"trainee2@example.com"
		);
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));

		var response = service.inviteTrainees(
				cohortId,
				new RegisterTraineesRequest(List.of(first, second)),
				"lead@example.com",
				"direct-1"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		assertThat(response.registeredCount()).isEqualTo(2);
		assertThat(response.invitationSentCount()).isEqualTo(2);
		assertThat(response.failures()).isEmpty();
		verify(invitationDispatcher).inviteTrainee(context, first, actor, "direct-1:1");
		verify(invitationDispatcher).inviteTrainee(context, second, actor, "direct-1:2");
	}

	@Test
	void directInvitationReportsInvalidEmailByInputRow() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.LEAD_MANAGER, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));

		var response = service.inviteTrainees(
				cohortId,
				new RegisterTraineesRequest(List.of(
						new RegisterTraineesRequest.Trainee("홍길동", "fdsafsdafsafsaffa")
				)),
				"lead@example.com",
				"direct-invalid"
		);

		assertThat(response.requestedCount()).isEqualTo(1);
		assertThat(response.registeredCount()).isZero();
		assertThat(response.invitationSentCount()).isZero();
		assertThat(response.failures()).containsExactly(
				new RegisterTraineesResponse.Failure(1, "fdsafsdafsafsaffa", 1)
		);
		verify(invitationDispatcher, never()).inviteTrainee(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void csvInvitationReportsInvalidAndExistingEmailsByCsvRow() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.LEAD_MANAGER, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));
		when(invitationRepository.existsOrganizationTraineeByNormalizedEmail(
				organizationId,
				"existing@example.com"
		)).thenReturn(true);

		var response = service.inviteTraineesFromCsv(
				cohortId,
				List.of(
						new TraineeCsvRow(2, "형식 오류", "invalid-email"),
						new TraineeCsvRow(3, "기존 사용자", "existing@example.com")
				),
				"lead@example.com",
				"batch-2"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		assertThat(response.registeredCount()).isZero();
		assertThat(response.invitationSentCount()).isZero();
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::row)
				.containsExactly(2, 3);
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::status)
				.containsExactly(1, 3);
		verify(invitationDispatcher, never()).inviteTrainee(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void rejectsTraineeInvitationForAnotherOrganizationCohort() {
		UUID actorOrganizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.LEAD_MANAGER, actorOrganizationId);
		InvitationContext context = new InvitationContext(UUID.randomUUID(), "Other", cohortId, "1기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));

		assertThatThrownBy(() -> service.inviteTraineesFromCsv(
				cohortId,
				List.of(new TraineeCsvRow(2, "교육생", "trainee@example.com")),
				"lead@example.com",
				null
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("다른 기관");
	}

	private AuthUser actor(Role role, UUID organizationId) {
		return new AuthUser(
				UUID.randomUUID(),
				organizationId,
				role == Role.SUPER_ADMIN ? "admin@example.com" : "lead@example.com",
				"Actor",
				"hash",
				"ACTIVE",
				true,
				null,
				role,
				organizationId == null ? null : "ACTIVE"
		);
	}

	private PendingInvitation pendingInvitation(InvitationContext context, String email, Role role) {
		Instant now = Instant.now();
		return new PendingInvitation(
				UUID.randomUUID(),
				UUID.randomUUID(),
				email,
				"raw-token",
				role,
				now,
				now.plusSeconds(3600),
				context
		);
	}
}
