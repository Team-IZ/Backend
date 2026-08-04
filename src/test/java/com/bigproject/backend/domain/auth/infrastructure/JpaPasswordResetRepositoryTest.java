package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.infrastructure.jpa.AuditLogJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.RefreshTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.UserInvitationJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaPasswordResetRepositoryTest {
	private final AuthUserJpaRepository userRepository = mock(AuthUserJpaRepository.class);
	private final OneTimeTokenJpaRepository tokenRepository = mock(OneTimeTokenJpaRepository.class);
	private final UserInvitationJpaRepository invitationRepository = mock(UserInvitationJpaRepository.class);
	private final RefreshTokenJpaRepository refreshTokenRepository = mock(RefreshTokenJpaRepository.class);
	private final AuditLogJpaRepository auditLogRepository = mock(AuditLogJpaRepository.class);
	private final JpaPasswordResetRepository repository = new JpaPasswordResetRepository(
			userRepository,
			tokenRepository,
			invitationRepository,
			refreshTokenRepository,
			auditLogRepository
	);

	@Test
	void mapsLockedTokenAndDelegatesPasswordAndSessionUpdates() {
		UUID userId = UUID.randomUUID();
		UUID tokenId = UUID.randomUUID();
		Instant expiresAt = Instant.parse("2026-08-03T01:00:00Z");
		Instant changedAt = Instant.parse("2026-08-03T00:00:00Z");
		OneTimeTokenJpaRepository.PasswordResetTokenProjection row =
				mock(OneTimeTokenJpaRepository.PasswordResetTokenProjection.class);
		when(row.getTokenId()).thenReturn(tokenId);
		when(row.getUserId()).thenReturn(userId);
		when(row.getEmail()).thenReturn("user@example.com");
		when(row.getNormalizedEmail()).thenReturn("user@example.com");
		when(row.getPasswordHash()).thenReturn("old-hash");
		when(row.getUserStatus()).thenReturn("ACTIVE");
		when(row.getPurpose()).thenReturn("PASSWORD_RESET");
		when(row.getExpiresAt()).thenReturn(expiresAt);
		when(tokenRepository.findPasswordResetTokenForUpdate("token-hash")).thenReturn(Optional.of(row));
		when(userRepository.updatePassword(userId, "new-hash", changedAt)).thenReturn(1);

		var token = repository.findTokenForUpdate("token-hash");
		boolean passwordUpdated = repository.updatePassword(userId, "new-hash", changedAt);
		repository.revokeAllRefreshTokens(userId, changedAt);

		assertThat(token).hasValueSatisfying(found -> {
			assertThat(found.tokenId()).isEqualTo(tokenId);
			assertThat(found.userStatus()).isEqualTo("ACTIVE");
			assertThat(found.expiresAt()).isEqualTo(expiresAt);
		});
		assertThat(passwordUpdated).isTrue();
		verify(refreshTokenRepository).revokeAllForPasswordChange(userId, changedAt);
	}
}
