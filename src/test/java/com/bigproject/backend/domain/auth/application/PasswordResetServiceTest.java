package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.domain.PasswordResetToken;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {
	private static final String RAW_TOKEN = "raw-reset-token";
	private static final String REQUEST_ID = UUID.randomUUID().toString();
	private static final String CURRENT_PASSWORD = "Current1!";
	private static final String NEW_PASSWORD = "Changed1!";

	private final PasswordResetRequestDispatcher dispatcher = mock(PasswordResetRequestDispatcher.class);
	private final PasswordResetRepository repository = mock(PasswordResetRepository.class);
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
	private final OneTimeTokenHasher tokenHasher = new OneTimeTokenHasher();
	private final PasswordResetAuditLogger auditLogger = mock(PasswordResetAuditLogger.class);
	private final PasswordResetService service = new PasswordResetService(
			dispatcher,
			repository,
			tokenHasher,
			passwordEncoder,
			new PasswordPolicy(),
			auditLogger
	);

	@Test
	void alwaysReturnsSameAcceptedMessageWhenDispatchFails() {
		doThrow(new IllegalStateException("smtp unavailable"))
				.when(dispatcher).dispatch("user@example.com", REQUEST_ID);

		var response = service.request(" User@Example.com ", REQUEST_ID);

		assertThat(response.message()).contains("계정에 등록돼 있으면");
		verify(auditLogger).recordFailure(null, REQUEST_ID, "RESET_MAIL_FAILED");
	}

	@Test
	void changesPasswordConsumesTokenAndRevokesAllRefreshTokens() {
		PasswordResetToken token = validToken();
		when(repository.findTokenForUpdate(tokenHasher.hash(RAW_TOKEN))).thenReturn(Optional.of(token));
		when(repository.updatePassword(eq(token.userId()), any(), any())).thenReturn(true);
		when(repository.markTokenUsed(eq(token.tokenId()), eq(REQUEST_ID), any())).thenReturn(true);

		var response = service.confirm(request(NEW_PASSWORD), REQUEST_ID);

		assertThat(response.status()).isEqualTo("COMPLETED");
		verify(repository).invalidateOtherResetTokens(eq(token.userId()), eq(token.tokenId()), any());
		verify(repository).revokeAllRefreshTokens(eq(token.userId()), any());
		verify(auditLogger).recordConfirmationSuccess(token, REQUEST_ID);
	}

	@Test
	void rejectsWeakPasswordWithStableCode() {
		PasswordResetToken token = validToken();
		when(repository.findTokenForUpdate(any())).thenReturn(Optional.of(token));

		assertThatThrownBy(() -> service.confirm(request("weak"), REQUEST_ID))
				.isInstanceOfSatisfying(PasswordResetException.class, exception -> {
					assertThat(exception.code()).isEqualTo("WEAK_PASSWORD");
					assertThat(exception.status().value()).isEqualTo(422);
				});
	}

	@Test
	void rejectsCurrentPasswordReuse() {
		PasswordResetToken token = validToken();
		when(repository.findTokenForUpdate(any())).thenReturn(Optional.of(token));

		assertThatThrownBy(() -> service.confirm(request(CURRENT_PASSWORD), REQUEST_ID))
				.isInstanceOfSatisfying(PasswordResetException.class,
						exception -> assertThat(exception.code()).isEqualTo("SAME_AS_CURRENT"));
	}

	@Test
	void distinguishesExpiredAndUsedTokens() {
		PasswordResetToken expired = token(Instant.now().minusSeconds(1), null, null);
		when(repository.findTokenForUpdate(any())).thenReturn(Optional.of(expired));
		assertThatThrownBy(() -> service.confirm(request(NEW_PASSWORD), REQUEST_ID))
				.isInstanceOfSatisfying(PasswordResetException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESET_TOKEN_EXPIRED"));

		PasswordResetToken used = token(Instant.now().plusSeconds(60), Instant.now(), null);
		when(repository.findTokenForUpdate(any())).thenReturn(Optional.of(used));
		assertThatThrownBy(() -> service.confirm(request(NEW_PASSWORD), REQUEST_ID))
				.isInstanceOfSatisfying(PasswordResetException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESET_TOKEN_USED"));
	}

	@Test
	void rejectsUnknownTokenWithoutLeakingDetails() {
		when(repository.findTokenForUpdate(any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.confirm(request(NEW_PASSWORD), REQUEST_ID))
				.isInstanceOfSatisfying(PasswordResetException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESET_TOKEN_INVALID"));
	}

	private PasswordResetConfirmationRequest request(String password) {
		return new PasswordResetConfirmationRequest(RAW_TOKEN, password, password);
	}

	private PasswordResetToken validToken() {
		return token(Instant.now().plusSeconds(300), null, null);
	}

	private PasswordResetToken token(Instant expiresAt, Instant usedAt, Instant invalidatedAt) {
		return new PasswordResetToken(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"user@example.com",
				"user@example.com",
				passwordEncoder.encode(CURRENT_PASSWORD),
				"ACTIVE",
				"PASSWORD_RESET",
				expiresAt,
				usedAt,
				invalidatedAt
		);
	}
}
