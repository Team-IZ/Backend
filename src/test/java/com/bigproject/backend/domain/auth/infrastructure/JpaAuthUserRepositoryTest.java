package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaAuthUserRepositoryTest {
	private final AuthUserJpaRepository jpaRepository = mock(AuthUserJpaRepository.class);
	private final JpaAuthUserRepository repository = new JpaAuthUserRepository(jpaRepository);

	@Test
	void mapsJpaProjectionToAuthDomainUser() {
		UUID organizationId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		Instant passwordChangedAt = Instant.parse("2026-08-03T00:00:00Z");
		AuthUserJpaRepository.AuthUserProjection row = mock(AuthUserJpaRepository.AuthUserProjection.class);
		when(row.getUserId()).thenReturn(userId);
		when(row.getOrganizationId()).thenReturn(organizationId);
		when(row.getEmail()).thenReturn("operator@example.com");
		when(row.getName()).thenReturn("Operator");
		when(row.getPasswordHash()).thenReturn("encoded-password");
		when(row.getStatus()).thenReturn("ACTIVE");
		when(row.getEmailVerified()).thenReturn(true);
		when(row.getPasswordChangedAt()).thenReturn(passwordChangedAt);
		when(row.getRoleCode()).thenReturn("OPERATOR");
		when(row.getOrganizationStatus()).thenReturn("ACTIVE");
		when(jpaRepository.findAuthUser("operator@example.com")).thenReturn(Optional.of(row));

		var user = repository.findByNormalizedEmail("operator@example.com");

		assertThat(user).hasValueSatisfying(found -> {
			assertThat(found.userId()).isEqualTo(userId);
			assertThat(found.organizationId()).isEqualTo(organizationId);
			assertThat(found.role()).isEqualTo(Role.OPERATOR);
			assertThat(found.passwordChangedAt()).isEqualTo(passwordChangedAt);
			assertThat(found.organizationStatus()).isEqualTo("ACTIVE");
		});
	}

	@Test
	void rejectsLastLoginUpdateWhenNoUserWasUpdated() {
		UUID userId = UUID.randomUUID();
		Instant lastLoginAt = Instant.parse("2026-08-03T00:00:00Z");
		when(jpaRepository.updateLastLoginAt(userId, lastLoginAt)).thenReturn(0);

		assertThatThrownBy(() -> repository.updateLastLoginAt(userId, lastLoginAt))
				.isInstanceOf(IllegalStateException.class);
		verify(jpaRepository).updateLastLoginAt(userId, lastLoginAt);
	}
}
