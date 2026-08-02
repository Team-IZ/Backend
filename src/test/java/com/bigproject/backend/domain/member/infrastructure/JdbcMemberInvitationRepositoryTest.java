package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcMemberInvitationRepositoryTest {
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final JdbcMemberInvitationRepository repository = new JdbcMemberInvitationRepository(jdbcTemplate);

	@Test
	void createsPendingUserWithoutPartialCommitEmailState() {
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

		repository.createPendingUser(
				UUID.randomUUID(),
				"operator@example.com",
				"operator@example.com",
				"operator@example.com",
				Role.OPERATOR,
				"pending-password-hash",
				Instant.parse("2026-08-02T00:00:00Z")
		);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sql.capture(), parameters.capture());
		assertThat(sql.getValue())
				.contains("INSERT INTO app_user")
				.doesNotContain("commit_email_status")
				.doesNotContain("'UNVERIFIED'");
		assertThat(parameters.getValue()).hasSize(10);
	}

	@Test
	void findsIncompleteInvitationByNormalizedEmail() {
		String email = "invited@example.com";
		when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(email))).thenReturn(true);

		assertThat(repository.existsIncompleteInvitationByNormalizedEmail(email)).isTrue();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class), eq(email));
		assertThat(sql.getValue())
				.contains("FROM user_invitation")
				.contains("target_email_normalized = ?")
				.contains("'PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED'");
	}

	@Test
	void findsExistingTraineeEmailThroughRoleAndOrganization() {
		UUID organizationId = UUID.randomUUID();
		String email = "existing@example.com";
		when(jdbcTemplate.queryForObject(
				anyString(),
				eq(Boolean.class),
				eq(email),
				eq(organizationId)
		)).thenReturn(true);

		boolean exists = repository.existsOrganizationTraineeByNormalizedEmail(organizationId, email);

		assertThat(exists).isTrue();
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(
				sql.capture(),
				eq(Boolean.class),
				eq(email),
				eq(organizationId)
		);
		assertThat(sql.getValue())
				.contains("JOIN \"role\"")
				.contains("JOIN organization")
				.contains("u.org_id = ?")
				.contains("r.code = 'TRAINEE'");
	}

	@Test
	void createsRoleScopedInvitationLedger() {
		when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
		UUID organizationId = UUID.randomUUID();
		UUID cohortId = UUID.randomUUID();
		UUID invitedBy = UUID.randomUUID();

		UUID invitationId = repository.createInvitation(
				organizationId,
				"manager@example.com",
				"manager@example.com",
				Role.MANAGER,
				cohortId,
				null,
				invitedBy,
				Instant.parse("2026-08-02T00:00:00Z")
		);

		assertThat(invitationId).isNotNull();
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sql.capture(), parameters.capture());
		assertThat(sql.getValue())
				.contains("INSERT INTO user_invitation")
				.contains("target_role_code")
				.contains("target_cohort_id")
				.contains("target_class_id");
		assertThat(parameters.getValue()).contains(Role.MANAGER.name(), cohortId, invitedBy);
	}

	@Test
	void linksOneTimeTokenToInvitationLedger() {
		UUID invitationId = UUID.randomUUID();
		Instant issuedAt = Instant.parse("2026-08-02T00:00:00Z");
		InvitationToken token = new InvitationToken(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				invitationId,
				"operator@example.com",
				"operator@example.com",
				InvitationPurpose.INVITE_OPERATOR_MANAGER,
				"hash",
				"{}",
				issuedAt,
				issuedAt.plusSeconds(3600),
				UUID.randomUUID(),
				"request-1"
		);

		repository.saveToken(token);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(sql.capture(), parameters.capture());
		assertThat(sql.getValue())
				.contains("INSERT INTO one_time_token")
				.contains("invitation_id");
		assertThat(parameters.getValue()).contains(invitationId);
	}
}
