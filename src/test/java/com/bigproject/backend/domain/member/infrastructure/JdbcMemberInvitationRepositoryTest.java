package com.bigproject.backend.domain.member.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcMemberInvitationRepositoryTest {
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final JdbcMemberInvitationRepository repository = new JdbcMemberInvitationRepository(jdbcTemplate);

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
}
