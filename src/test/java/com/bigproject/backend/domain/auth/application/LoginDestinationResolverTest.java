package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.LoginCohortRepository;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginDestinationResolverTest {
	private final LoginCohortRepository repository = mock(LoginCohortRepository.class);
	private final LoginDestinationResolver resolver = new LoginDestinationResolver(repository);

	@Test
	void resolvesFixedDestinationsForSuperAdminAndTrainee() {
		assertThat(resolver.resolveRedirectPath(user(Role.SUPER_ADMIN, null))).isEqualTo("/admin/orgs");
		assertThat(resolver.resolveRedirectPath(user(Role.TRAINEE, UUID.randomUUID()))).isEqualTo("/home");
	}

	@Test
	void resolvesLatestOrganizationCohortForOperator() {
		UUID organizationId = UUID.randomUUID();
		when(repository.findLatestNameForOrganization(organizationId)).thenReturn(Optional.of("AIVLE/7기"));

		String redirectPath = resolver.resolveRedirectPath(user(Role.OPERATOR, organizationId));

		assertThat(redirectPath).isEqualTo("/cohorts/AIVLE%2F7%EA%B8%B0");
	}

	@Test
	void resolvesLatestAssignedCohortForManager() {
		UUID organizationId = UUID.randomUUID();
		AuthUser manager = user(Role.MANAGER, organizationId);
		when(repository.findLatestAssignedNameForManager(eq(organizationId), eq(manager.userId()), any(Instant.class)))
				.thenReturn(Optional.of("7기 심화"));

		String redirectPath = resolver.resolveRedirectPath(manager);

		assertThat(redirectPath).isEqualTo("/cohorts/7%EA%B8%B0%20%EC%8B%AC%ED%99%94");
	}

	@Test
	void fallsBackToCohortListWhenNoAccessibleCohortExists() {
		UUID organizationId = UUID.randomUUID();
		when(repository.findLatestNameForOrganization(organizationId)).thenReturn(Optional.empty());

		assertThat(resolver.resolveRedirectPath(user(Role.OPERATOR, organizationId))).isEqualTo("/cohorts");
	}

	private AuthUser user(Role role, UUID organizationId) {
		return new AuthUser(
				UUID.randomUUID(),
				organizationId,
				"user@example.com",
				"테스트 사용자",
				"password-hash",
				"ACTIVE",
				true,
				null,
				role,
				organizationId == null ? null : "ACTIVE"
		);
	}
}
