package com.bigproject.backend.domain.member.infrastructure;

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

class JdbcTraineeRosterInvitationSqlTest {

	@Test
	void cohortTotalCombinesAcceptedMembersAndPendingInvitations() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), any(Object[].class)))
				.thenReturn(2);
		JdbcTraineeRosterRepository repository = new JdbcTraineeRosterRepository(jdbcTemplate);

		repository.countCohortTotal(UUID.randomUUID(), UUID.randomUUID(), null);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), any(Object[].class));
		assertThat(sql.getValue())
				.contains("FROM cohort_member")
				.contains("UNION ALL")
				.contains("FROM user_invitation")
				.contains("u.status = 'PENDING'")
				.contains("ui.target_role_code = 'TRAINEE'")
				.contains("invitation_cohort.status <> 'CLOSED'")
				.contains("FROM roster r");
	}
}
