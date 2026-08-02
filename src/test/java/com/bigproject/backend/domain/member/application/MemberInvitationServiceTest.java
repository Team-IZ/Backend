package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
	void superAdminInvitesOperatorWithoutClientSuppliedRole() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("operator@example.com", null, null);
		PendingInvitation invitation = pendingInvitation(context, request.email(), Role.OPERATOR);
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
		assertThat(response.role()).isEqualTo(Role.OPERATOR);
		verify(invitationDispatcher).inviteManager(context, request, actor, "request-1");
	}

	@Test
	void reportsUnknownManagerInvitationConstraintAsServerError() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest(
				"operator@example.com",
				null,
				null
		);
		DataIntegrityViolationException databaseException = new DataIntegrityViolationException("purpose check");
		when(authUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, actor, "request-constraint"))
				.thenThrow(databaseException);

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				request,
				"admin@example.com",
				"request-constraint"
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("500 INTERNAL_SERVER_ERROR")
				.hasMessageContaining("초대 정보를 저장할 수 없습니다.")
				.hasCause(databaseException);
	}

	@Test
	void reportsConcurrentInvitationConstraintAsConflict() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("operator@example.com", null, null);
		DataIntegrityViolationException databaseException = new DataIntegrityViolationException("unique violation");
		when(authUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, actor, "request-race"))
				.thenThrow(databaseException);
		when(invitationRepository.existsIncompleteInvitationByNormalizedEmail("operator@example.com"))
				.thenReturn(true);

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				request,
				"admin@example.com",
				"request-race"
		)).isInstanceOf(InvitationConflictException.class)
				.hasMessage("이미 등록되었거나 초대된 이메일입니다.")
				.hasCause(databaseException);
	}

	@Test
	void operatorInvitesManagerInOwnOrganization() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.OPERATOR, organizationId);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest(
				"manager@example.com",
				UUID.randomUUID(),
				UUID.randomUUID()
		);
		PendingInvitation invitation = pendingInvitation(context, request.email(), Role.MANAGER);
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, actor, "request-2"))
				.thenReturn(invitation);

		var response = service.inviteManager(organizationId, request, "lead@example.com", "request-2");

		assertThat(response.role()).isEqualTo(Role.MANAGER);
		verify(invitationDispatcher).inviteManager(context, request, actor, "request-2");
	}

	@Test
	void rejectsOperatorInvitationWithManagerScopeFromSuperAdmin() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("operator@example.com", UUID.randomUUID(), null),
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
	void rejectsManagerInvitationWithoutCohort() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.OPERATOR, organizationId)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("manager@example.com", null, null),
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
	void rejectsOperatorInvitationWithCohortAssignment() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest(
						"operator@example.com",
						UUID.randomUUID(),
						null
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
	void rejectsManagerFromSendingOperatorOrManagerInvitation() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.MANAGER, organizationId)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("target@example.com", UUID.randomUUID(), null),
				"lead@example.com",
				null
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("슈퍼어드민과 오퍼레이터만");
		verify(invitationDispatcher, never()).inviteManager(
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void rejectsSuperAdminFromInvitingTrainees() {
		UUID cohortId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteTraineesFromCsv(
				cohortId,
				List.of(new TraineeCsvRow(2, "교육생", "trainee@example.com")),
				"admin@example.com",
				null
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("오퍼레이터만");
		verify(invitationDispatcher, never()).inviteTrainee(
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
		AuthUser actor = actor(Role.OPERATOR, organizationId);
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
		AuthUser actor = actor(Role.OPERATOR, organizationId);
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
		AuthUser actor = actor(Role.OPERATOR, organizationId);
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
		AuthUser actor = actor(Role.OPERATOR, organizationId);
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
		AuthUser actor = actor(Role.OPERATOR, actorOrganizationId);
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
