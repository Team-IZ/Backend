package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationPersistenceServiceTest {

	@Test
	void rejectsIncompleteInvitationEvenWithoutPendingUser() {
		MemberInvitationRepository repository = mock(MemberInvitationRepository.class);
		when(repository.existsIncompleteInvitationByNormalizedEmail("operator@example.com")).thenReturn(true);
		InvitationPersistenceService service = new InvitationPersistenceService(
				repository,
				mock(OneTimeTokenGenerator.class),
				new OneTimeTokenHasher(),
				new BCryptPasswordEncoder(),
				new ObjectMapper()
		);
		AuthUser actor = new AuthUser(
				UUID.randomUUID(), null, "admin@example.com", "Admin", "hash",
				"ACTIVE", true, null, Role.SUPER_ADMIN, null
		);

		assertThatThrownBy(() -> service.createManagerInvitation(
				InvitationContext.organization(UUID.randomUUID(), "AIVLE"),
				new InviteManagerRequest("Operator@Example.com", null),
				Role.OPERATOR,
				actor,
				"request-duplicate"
		)).isInstanceOf(InvitationConflictException.class)
				.hasMessage("이미 등록되었거나 초대된 이메일입니다.");
	}

	@Test
	void storesOnlyHashOfRawInvitationToken() {
		MemberInvitationRepository repository = mock(MemberInvitationRepository.class);
		UUID memberId = UUID.randomUUID();
		when(repository.createPendingUser(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(memberId);
		OneTimeTokenGenerator tokenGenerator = mock(OneTimeTokenGenerator.class);
		when(tokenGenerator.generate()).thenReturn("raw-secret-token");
		InvitationPersistenceService service = new InvitationPersistenceService(
				repository,
				tokenGenerator,
				new OneTimeTokenHasher(),
				new BCryptPasswordEncoder(),
				new ObjectMapper()
		);
		ReflectionTestUtils.setField(service, "invitationExpiration", Duration.ofHours(24));
		UUID organizationId = UUID.randomUUID();
		AuthUser actor = new AuthUser(
				UUID.randomUUID(),
				null,
				"admin@example.com",
				"Admin",
				"hash",
				"ACTIVE",
				true,
				null,
				Role.SUPER_ADMIN,
				null
		);

		var invitation = service.createManagerInvitation(
				InvitationContext.organization(organizationId, "AIVLE"),
				new InviteManagerRequest("Operator@Example.com", null),
				Role.OPERATOR,
				actor,
				"request-1"
		);

		ArgumentCaptor<InvitationToken> tokenCaptor = ArgumentCaptor.forClass(InvitationToken.class);
		verify(repository).saveToken(tokenCaptor.capture());
		InvitationToken storedToken = tokenCaptor.getValue();
		assertThat(invitation.rawToken()).isEqualTo("raw-secret-token");
		assertThat(storedToken.tokenHash()).isNotEqualTo(invitation.rawToken());
		assertThat(storedToken.tokenHash()).hasSize(64);
		assertThat(storedToken.purpose()).isEqualTo(InvitationPurpose.INVITE_OPERATOR_MANAGER);
		assertThat(storedToken.normalizedTargetEmail()).isEqualTo("operator@example.com");
		assertThat(storedToken.payload()).contains("\"schemaVersion\":1", "\"role\":\"OPERATOR\"");
	}

	@Test
	void storesManagerTargetScopeWithoutRequiringInitialClassAssignment() {
		MemberInvitationRepository repository = mock(MemberInvitationRepository.class);
		when(repository.createPendingUser(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(UUID.randomUUID());
		OneTimeTokenGenerator tokenGenerator = mock(OneTimeTokenGenerator.class);
		when(tokenGenerator.generate()).thenReturn("raw-manager-token");
		InvitationPersistenceService service = new InvitationPersistenceService(
				repository,
				tokenGenerator,
				new OneTimeTokenHasher(),
				new BCryptPasswordEncoder(),
				new ObjectMapper()
		);
		ReflectionTestUtils.setField(service, "invitationExpiration", Duration.ofHours(24));
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = new AuthUser(
				UUID.randomUUID(), organizationId, "operator@example.com", "Operator", "hash",
				"ACTIVE", true, null, Role.OPERATOR, "ACTIVE"
		);

		service.createManagerInvitation(
				InvitationContext.organization(organizationId, "AIVLE"),
				new InviteManagerRequest("manager@example.com", cohortId),
				Role.MANAGER,
				actor,
				"request-manager"
		);

		verify(repository).validateCohort(organizationId, cohortId);
		verify(repository).createPendingUser(
				eq(organizationId),
				eq("manager@example.com"),
				eq("manager@example.com"),
				// 이름은 비워 둔다 — 초대받은 본인이 가입할 때 정한다.
				// 예전에는 이메일을 넣어서 목록 이름 칸에 `—` 대신 이메일이 그대로 보였다.
				isNull(),
				eq(Role.MANAGER),
				any(),
				any()
		);
	}

	@Test
	void defersTraineeMembershipUntilInvitationAcceptance() {
		MemberInvitationRepository repository = mock(MemberInvitationRepository.class);
		UUID memberId = UUID.randomUUID();
		when(repository.createPendingUser(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(memberId);
		OneTimeTokenGenerator tokenGenerator = mock(OneTimeTokenGenerator.class);
		when(tokenGenerator.generate()).thenReturn("raw-trainee-token");
		InvitationPersistenceService service = new InvitationPersistenceService(
				repository,
				tokenGenerator,
				new OneTimeTokenHasher(),
				new BCryptPasswordEncoder(),
				new ObjectMapper()
		);
		ReflectionTestUtils.setField(service, "invitationExpiration", Duration.ofHours(24));
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		AuthUser actor = new AuthUser(
				UUID.randomUUID(),
				organizationId,
				"lead@example.com",
				"Lead",
				"hash",
				"ACTIVE",
				true,
				null,
				Role.OPERATOR,
				"ACTIVE"
		);

		service.createTraineeInvitation(
				new InvitationContext(organizationId, "AIVLE", cohortId, "7기"),
				new RegisterTraineesRequest.Trainee("교육생", "trainee@example.com"),
				actor,
				"request-2"
		);

		ArgumentCaptor<InvitationToken> tokenCaptor = ArgumentCaptor.forClass(InvitationToken.class);
		verify(repository).saveToken(tokenCaptor.capture());
		assertThat(tokenCaptor.getValue().payload())
				.contains("\"name\":\"교육생\"")
				.doesNotContain("classroomId");
	}
}
