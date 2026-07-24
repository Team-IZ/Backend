package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.ManagerInvitationRole;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationPersistenceServiceTest {

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
				new InviteManagerRequest("Lead@Example.com", ManagerInvitationRole.LEAD_MANAGER, null, List.of()),
				actor,
				"request-1"
		);

		ArgumentCaptor<InvitationToken> tokenCaptor = ArgumentCaptor.forClass(InvitationToken.class);
		verify(repository).saveToken(tokenCaptor.capture());
		InvitationToken storedToken = tokenCaptor.getValue();
		assertThat(invitation.rawToken()).isEqualTo("raw-secret-token");
		assertThat(storedToken.tokenHash()).isNotEqualTo(invitation.rawToken());
		assertThat(storedToken.tokenHash()).hasSize(64);
		assertThat(storedToken.purpose()).isEqualTo(InvitationPurpose.INVITE_MANAGER);
		assertThat(storedToken.normalizedTargetEmail()).isEqualTo("lead@example.com");
		assertThat(storedToken.payload()).contains("\"schemaVersion\":1", "\"role\":\"LEAD_MANAGER\"");
	}

	@Test
	void createsTraineeInvitationWithoutClassroomAssignment() {
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
				Role.LEAD_MANAGER,
				"ACTIVE"
		);

		var invitation = service.createTraineeInvitation(
				new InvitationContext(organizationId, "AIVLE", cohortId, "7기"),
				new RegisterTraineesRequest.Trainee("교육생", "trainee@example.com"),
				actor,
				"request-2"
		);

		verify(repository).saveTraineeMembership(
				memberId,
				invitation.tokenId(),
				organizationId,
				cohortId,
				null,
				actor.userId(),
				invitation.invitedAt()
		);
		ArgumentCaptor<InvitationToken> tokenCaptor = ArgumentCaptor.forClass(InvitationToken.class);
		verify(repository).saveToken(tokenCaptor.capture());
		assertThat(tokenCaptor.getValue().payload())
				.contains("\"name\":\"교육생\"")
				.doesNotContain("classroomId");
	}
}
