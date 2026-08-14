package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberInvitationServiceTest {
	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final MemberInvitationRepository invitationRepository = mock(MemberInvitationRepository.class);
	private final TransactionalInvitationDispatcher invitationDispatcher = mock(TransactionalInvitationDispatcher.class);
	private final AsyncTraineeInvitationMailer asyncMailer = mock(AsyncTraineeInvitationMailer.class);
	private final MemberInvitationService service = new MemberInvitationService(
			authUserRepository,
			invitationRepository,
			invitationDispatcher,
			asyncMailer
	);

	@Test
	void superAdminInvitesOperatorWithExplicitTargetRole() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("operator@example.com", null);
		PendingInvitation invitation = pendingInvitation(context, request.email(), Role.OPERATOR);
		when(authUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, Role.OPERATOR, actor, "request-1"))
				.thenReturn(invitation);

		var response = service.inviteManager(
				organizationId,
				request,
				Role.OPERATOR,
				"ADMIN@example.com",
				"request-1"
		);

		assertThat(response.memberId()).isEqualTo(invitation.memberId());
		assertThat(response.role()).isEqualTo(Role.OPERATOR);
		verify(invitationDispatcher).inviteManager(context, request, Role.OPERATOR, actor, "request-1");
	}

	@Test
	void reportsUnknownManagerInvitationConstraintAsServerError() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("operator@example.com", null);
		DataIntegrityViolationException databaseException = new DataIntegrityViolationException("purpose check");
		when(authUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, Role.OPERATOR, actor, "request-constraint"))
				.thenThrow(databaseException);

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				request,
				Role.OPERATOR,
				"admin@example.com",
				"request-constraint"
		)).isInstanceOfSatisfying(ApiException.class, exception -> {
					// 상태는 이제 예외 메시지가 아니라 코드가 들고 있다.
					assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.INVITATION_SAVE_FAILED);
					assertThat(exception.errorCode().status().value()).isEqualTo(500);
					assertThat(exception.getMessage()).contains("초대 정보를 저장할 수 없습니다.");
					assertThat(exception).hasCause(databaseException);
				});
	}

	@Test
	void reportsConcurrentInvitationConstraintAsConflict() {
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = actor(Role.SUPER_ADMIN, null);
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("operator@example.com", null);
		DataIntegrityViolationException databaseException = new DataIntegrityViolationException("unique violation");
		when(authUserRepository.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, Role.OPERATOR, actor, "request-race"))
				.thenThrow(databaseException);
		when(invitationRepository.existsIncompleteInvitationByNormalizedEmail("operator@example.com"))
				.thenReturn(true);

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				request,
				Role.OPERATOR,
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
		InviteManagerRequest request = new InviteManagerRequest("manager@example.com", UUID.randomUUID());
		PendingInvitation invitation = pendingInvitation(context, request.email(), Role.MANAGER);
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findActiveOrganization(organizationId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.inviteManager(context, request, Role.MANAGER, actor, "request-2"))
				.thenReturn(invitation);

		var response = service.inviteManager(organizationId, request, Role.MANAGER, "lead@example.com", "request-2");

		assertThat(response.role()).isEqualTo(Role.MANAGER);
		verify(invitationDispatcher).inviteManager(context, request, Role.MANAGER, actor, "request-2");
	}

	/** 매니저 초대 경로가 슈퍼어드민에게 열려 있으면 오퍼레이터 초대와 중복된다. 역할별로 초대 주체가 하나여야 한다. */
	@Test
	void rejectsSuperAdminFromInvitingManager() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("manager@example.com", UUID.randomUUID()),
				Role.MANAGER,
				"admin@example.com",
				null
		)).isInstanceOf(ApiException.class)
				.hasMessageContaining("오퍼레이터만 매니저를 초대할 수 있습니다");
		verifyNoInvitationDispatched();
	}

	@Test
	void rejectsOperatorFromInvitingAnotherOperator() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.OPERATOR, organizationId)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("operator@example.com", null),
				Role.OPERATOR,
				"lead@example.com",
				null
		)).isInstanceOf(ApiException.class)
				.hasMessageContaining("슈퍼어드민만 오퍼레이터를 초대할 수 있습니다");
		verifyNoInvitationDispatched();
	}

	@Test
	void rejectsManagerInvitationWithoutCohort() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.OPERATOR, organizationId)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("manager@example.com", null),
				Role.MANAGER,
				"lead@example.com",
				null
		)).isInstanceOf(ApiException.class)
				.hasMessageContaining("하나의 기수");
		verifyNoInvitationDispatched();
	}

	@Test
	void rejectsOperatorInvitationWithCohortAssignment() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("admin@example.com"))
				.thenReturn(Optional.of(actor(Role.SUPER_ADMIN, null)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("operator@example.com", UUID.randomUUID()),
				Role.OPERATOR,
				"admin@example.com",
				null
		)).isInstanceOf(ApiException.class)
				.hasMessageContaining("기수를 배정하지 않습니다");
		verifyNoInvitationDispatched();
	}

	@Test
	void rejectsManagerFromSendingOperatorOrManagerInvitation() {
		UUID organizationId = UUID.randomUUID();
		when(authUserRepository.findByNormalizedEmail("lead@example.com"))
				.thenReturn(Optional.of(actor(Role.MANAGER, organizationId)));

		assertThatThrownBy(() -> service.inviteManager(
				organizationId,
				new InviteManagerRequest("target@example.com", UUID.randomUUID()),
				Role.MANAGER,
				"lead@example.com",
				null
		)).isInstanceOf(ApiException.class)
				.hasMessageContaining("오퍼레이터만 매니저를 초대할 수 있습니다");
		verifyNoInvitationDispatched();
	}

	private void verifyNoInvitationDispatched() {
		verify(invitationDispatcher, never()).inviteManager(
				org.mockito.ArgumentMatchers.any(),
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
				"admin@example.com"
		)).isInstanceOf(ApiException.class)
				.hasMessageContaining("오퍼레이터만");
		verifyNoTraineeSlotReserved();
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
				"lead@example.com"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		assertThat(response.registeredCount()).isZero();
		assertThat(response.invitationSentCount()).isZero();
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::row)
				.containsExactly(2, 3);
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::status)
				.containsExactly(2, 2);
		verifyNoTraineeSlotReserved();
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
		when(invitationDispatcher.reserveTrainees(any(), anyList(), any(), any())).thenAnswer(invocation ->
				reserveAll(invocation.getArgument(1), Map.of()));

		var response = service.inviteTrainees(
				cohortId,
				new RegisterTraineesRequest(List.of(first, second)),
				"lead@example.com"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		assertThat(response.registeredCount()).isEqualTo(2);
		// 응답 시점에는 아직 발송이 시작되지도 않았다. 실제로 나간 수는 폴링이 답한다.
		assertThat(response.invitationSentCount()).isZero();
		// 배치 식별자는 서버가 만든다 — 클라이언트가 정하면 같은 값으로 두 번 등록했을 때 진행률이 섞인다.
		assertThat(response.batchRequestId()).isNotBlank();
		assertThat(UUID.fromString(response.batchRequestId())).isNotNull();
		assertThat(response.failures()).isEmpty();
		ArgumentCaptor<List<InvitationPersistenceService.TraineeSlotRequest>> captor =
				ArgumentCaptor.forClass(List.class);
		verify(invitationDispatcher)
				.reserveTrainees(eq(context), captor.capture(), eq(actor), eq(response.batchRequestId()));
		// 행 번호는 직접 입력이므로 1부터다. 요청 식별자는 배치 단위이고 행 번호는 확보 단계에서 붙는다.
		assertThat(captor.getValue())
				.extracting(
						InvitationPersistenceService.TraineeSlotRequest::row,
						InvitationPersistenceService.TraineeSlotRequest::email)
				.containsExactly(
						org.assertj.core.api.Assertions.tuple(1, first.email()),
						org.assertj.core.api.Assertions.tuple(2, second.email()));
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
				"lead@example.com"
		);

		assertThat(response.requestedCount()).isEqualTo(1);
		assertThat(response.registeredCount()).isZero();
		assertThat(response.invitationSentCount()).isZero();
		assertThat(response.failures()).containsExactly(
				new RegisterTraineesResponse.Failure(1, "fdsafsdafsafsaffa", 1)
		);
		verifyNoTraineeSlotReserved();
	}

	@Test
	void csvInvitationReportsInvalidAndExistingEmailsByCsvRow() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.OPERATOR, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));
		// 기존 이메일 판정은 명단 전량을 왕복 한 번으로 조회한다(행마다 부르지 않는다).
		when(invitationRepository.findExistingOrganizationTraineeEmails(eq(organizationId), anyCollection()))
				.thenReturn(Set.of("existing@example.com"));

		var response = service.inviteTraineesFromCsv(
				cohortId,
				List.of(
						new TraineeCsvRow(2, "형식 오류", "invalid-email"),
						new TraineeCsvRow(3, "기존 사용자", "existing@example.com")
				),
				"lead@example.com"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		assertThat(response.registeredCount()).isZero();
		assertThat(response.invitationSentCount()).isZero();
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::row)
				.containsExactly(2, 3);
		assertThat(response.failures()).extracting(RegisterTraineesResponse.Failure::status)
				.containsExactly(1, 3);
		verifyNoTraineeSlotReserved();
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
				"lead@example.com"
		)).isInstanceOf(ApiException.class)
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

	/**
	 * 발송을 <b>큐에 넣지 못해도</b> 등록은 성공으로 응답한다.
	 *
	 * <p>자리와 초대 원장은 이 시점에 이미 커밋됐다. 여기서 요청을 실패시키면 화면에는 "등록하지
	 * 못했습니다"가 뜨는데 실제로는 등록된 사람이 있는 상태가 된다 — 개선 A가 없앤 바로 그 증상이다.
	 *
	 * <p>발송되지 않은 행은 원장에 PENDING으로 남아 안전망({@code TraineeInvitationOutbox})이 이어받으므로
	 * 초대가 유실되지도 않는다.
	 */
	@Test
	void keepsRegistrationSuccessfulWhenMailQueueRejectsTheBatch() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.OPERATOR, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.reserveTrainees(any(), anyList(), any(), any())).thenAnswer(invocation ->
				reserveAll(invocation.getArgument(1), Map.of()));
		doThrow(new TaskRejectedException("queue full"))
				.when(asyncMailer).sendTraineeInvitations(any(), anyList());

		var response = service.inviteTraineesFromCsv(
				cohortId,
				List.of(
						new TraineeCsvRow(2, "보냄", "sent@example.com"),
						new TraineeCsvRow(3, "실패", "failed@example.com")
				),
				"lead@example.com"
		);

		assertThat(response.requestedCount()).isEqualTo(2);
		// 둘 다 계정·원장은 만들어졌다 — 발송만 시작되지 못했다.
		assertThat(response.registeredCount()).isEqualTo(2);
		assertThat(UUID.fromString(response.batchRequestId())).isNotNull();
		// 발송 문제는 행별 사유(1·2·3)가 아니다. failures는 등록 자체가 안 된 행만 담는다.
		assertThat(response.failures()).isEmpty();
	}

	/**
	 * 진행률은 <b>초대 원장 집계</b>에서 나오고, 잡 상태는 저장하지 않고 유도한다.
	 *
	 * <p>배포본이 셋이라 인메모리 진행률은 성립하지 않는다 — 2초 뒤 폴링이 다른 인스턴스로 가면
	 * "그런 잡 없음"이 된다. 저장된 잡 상태와 행 상태가 어긋날 여지도 없어야 한다.
	 */
	@Test
	void derivesRegistrationStatusFromTheInvitationLedger() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		when(invitationRepository.findBatchProgress("batch-1", organizationId, cohortId))
				.thenReturn(Optional.of(new MemberInvitationRepository.BatchProgress(900, 800, 0, 100)));

		TraineeRegistrationProgress running =
				service.findRegistrationProgress(cohortId, organizationId, "batch-1");

		assertThat(running.status()).isEqualTo(TraineeRegistrationProgress.Status.RUNNING);
		assertThat(running.invitationSentCount()).isEqualTo(800);

		// 남은 것이 없고 실패가 있으면 부분 성공이다 — 화면은 [초대 재발송]을 안내한다.
		when(invitationRepository.findBatchProgress("batch-1", organizationId, cohortId))
				.thenReturn(Optional.of(new MemberInvitationRepository.BatchProgress(900, 895, 5, 0)));
		assertThat(service.findRegistrationProgress(cohortId, organizationId, "batch-1").status())
				.isEqualTo(TraineeRegistrationProgress.Status.PARTIAL);

		when(invitationRepository.findBatchProgress("batch-1", organizationId, cohortId))
				.thenReturn(Optional.of(new MemberInvitationRepository.BatchProgress(900, 900, 0, 0)));
		assertThat(service.findRegistrationProgress(cohortId, organizationId, "batch-1").status())
				.isEqualTo(TraineeRegistrationProgress.Status.SUCCEEDED);
	}

	/** 남의 배치 식별자를 찍어 넣어도 남의 진행률이 보이면 안 된다 — 범위 밖은 없는 것과 같다. */
	@Test
	void rejectsRegistrationProgressOutsideTheCohortAndOrganization() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		when(invitationRepository.findBatchProgress("someone-elses-batch", organizationId, cohortId))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() ->
				service.findRegistrationProgress(cohortId, organizationId, "someone-elses-batch"))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("errorCode", MemberErrorCode.REGISTRATION_BATCH_NOT_FOUND);
	}

	/**
	 * 기존 이메일 판정은 명단 전량을 <b>왕복 한 번</b>으로 물어야 한다.
	 *
	 * <p>행마다 {@code existsOrganizationTraineeByNormalizedEmail}을 부르면 900명이 900 왕복이 된다.
	 * Supavisor를 거치는 원격 DB에서는 그 지연이 그대로 응답 시간에 쌓인다.
	 */
	@Test
	void looksUpExistingTraineeEmailsInOneRoundTrip() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.OPERATOR, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.reserveTrainees(any(), anyList(), any(), any())).thenAnswer(invocation ->
				reserveAll(invocation.getArgument(1), Map.of()));

		service.inviteTraineesFromCsv(
				cohortId,
				List.of(
						new TraineeCsvRow(2, "가", "a@example.com"),
						new TraineeCsvRow(3, "나", "b@example.com"),
						new TraineeCsvRow(4, "다", "c@example.com")
				),
				"lead@example.com"
		);

		ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(invitationRepository, times(1))
				.findExistingOrganizationTraineeEmails(eq(organizationId), captor.capture());
		assertThat(captor.getValue())
				.containsExactlyInAnyOrder("a@example.com", "b@example.com", "c@example.com");
		// 단건 조회는 충돌 복구 경로에만 남는다 — 정상 경로에서는 한 번도 불리지 않는다.
		verify(invitationRepository, never())
				.existsOrganizationTraineeByNormalizedEmail(any(), any());
	}

	/**
	 * 발송은 확보가 전부 끝난 뒤 <b>요청 스레드 밖에서 한 번</b> 불려야 한다.
	 *
	 * <p>행마다 부르면 연결 재사용이 무의미해지고, 요청 안에서 부르면 900명 기준 3분이 응답 시간에
	 * 그대로 쌓여 게이트웨이 상한을 넘는다 — 이 테스트가 없으면 발송을 다시 동기로 되돌려도 아무도 모른다.
	 */
	@Test
	void handsEveryReservedInvitationToTheAsyncMailerInOneCall() {
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = actor(Role.OPERATOR, organizationId);
		InvitationContext context = new InvitationContext(organizationId, "AIVLE", cohortId, "7기");
		when(authUserRepository.findByNormalizedEmail("lead@example.com")).thenReturn(Optional.of(actor));
		when(invitationRepository.findInvitableCohort(cohortId)).thenReturn(Optional.of(context));
		when(invitationDispatcher.reserveTrainees(any(), anyList(), any(), any())).thenAnswer(invocation ->
				reserveAll(invocation.getArgument(1), Map.of()));

		var response = service.inviteTraineesFromCsv(
				cohortId,
				List.of(
						new TraineeCsvRow(2, "가", "a@example.com"),
						new TraineeCsvRow(3, "나", "b@example.com"),
						new TraineeCsvRow(4, "다", "c@example.com")
				),
				"lead@example.com"
		);

		verify(invitationDispatcher, times(1)).reserveTrainees(any(), anyList(), any(), any());
		ArgumentCaptor<List<InvitationMailSender.TraineeInvitationMail>> captor =
				ArgumentCaptor.forClass(List.class);
		// 발송에 넘긴 식별자와 화면이 폴링할 식별자가 같아야 한다 — 갈라지면 진행률이 영영 404다.
		verify(asyncMailer, times(1))
				.sendTraineeInvitations(eq(response.batchRequestId()), captor.capture());
		assertThat(captor.getValue()).hasSize(3);
		// 요청 스레드가 직접 보내면 응답이 발송 시간만큼 늦어진다.
		verify(invitationDispatcher, never()).sendTraineeInvitations(anyList());
	}

	/** 모든 요청 행을 확보 성공으로 만든다. {@code overrides}에 있는 이메일은 지정한 초대를 쓴다. */
	private List<InvitationPersistenceService.TraineeSlot> reserveAll(
			List<InvitationPersistenceService.TraineeSlotRequest> requests,
			Map<String, PendingInvitation> overrides
	) {
		List<InvitationPersistenceService.TraineeSlot> slots = new ArrayList<>();
		for (InvitationPersistenceService.TraineeSlotRequest request : requests) {
			PendingInvitation invitation = overrides.get(request.email());
			slots.add(new InvitationPersistenceService.TraineeSlot(
					request,
					invitation != null ? invitation : pendingInvitation(null, request.email(), Role.TRAINEE)
			));
		}
		return slots;
	}

	private void verifyNoTraineeSlotReserved() {
		verify(invitationDispatcher, never()).reserveTrainees(any(), anyList(), any(), any());
		// 자리를 만들지 않았으면 보낼 것도 없다. 발송이 비동기라 응답만 봐서는 드러나지 않는다.
		verify(asyncMailer, never()).sendTraineeInvitations(any(), anyList());
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
