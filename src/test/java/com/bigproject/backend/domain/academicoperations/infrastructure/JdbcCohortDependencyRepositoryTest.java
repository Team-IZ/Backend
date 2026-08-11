package com.bigproject.backend.domain.academicoperations.infrastructure;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCohortDependencyRepositoryTest {

	@Test
	void pendingInvitationAlsoBlocksCohortDeletion() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
				.thenReturn(true);
		JdbcCohortDependencyRepository repository = new JdbcCohortDependencyRepository(jdbcTemplate);

		assertThat(repository.hasMembers(UUID.randomUUID())).isTrue();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class), any(Object[].class));
		assertThat(sql.getValue())
				.contains("FROM cohort_member")
				.contains("UNION ALL")
				.contains("FROM user_invitation")
				.contains("target_role_code = 'TRAINEE'");
	}
}
