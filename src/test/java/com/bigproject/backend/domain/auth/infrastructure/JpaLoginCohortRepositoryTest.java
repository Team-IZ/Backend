package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.infrastructure.jpa.LoginCohortJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaLoginCohortRepositoryTest {
	private final LoginCohortJpaRepository jpaRepository = mock(LoginCohortJpaRepository.class);
	private final JpaLoginCohortRepository repository = new JpaLoginCohortRepository(jpaRepository);

	@Test
	void returnsFirstLatestCohortResult() {
		UUID organizationId = UUID.randomUUID();
		UUID managerUserId = UUID.randomUUID();
		Instant asOf = Instant.parse("2026-08-03T00:00:00Z");
		when(jpaRepository.findLatestName(organizationId)).thenReturn(List.of("7기"));
		when(jpaRepository.findLatestAssignedName(organizationId, managerUserId, asOf))
				.thenReturn(List.of("6기"));

		assertThat(repository.findLatestNameForOrganization(organizationId)).contains("7기");
		assertThat(repository.findLatestAssignedNameForManager(organizationId, managerUserId, asOf))
				.contains("6기");
	}
}
