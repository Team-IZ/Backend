package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.auth.domain.AccountActivationRepository;
import com.bigproject.backend.domain.auth.domain.AccountActivationTarget;
import com.bigproject.backend.domain.auth.domain.ConsentCode;
import com.bigproject.backend.domain.auth.domain.ConsentRecord;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountActivationServiceTest {
	private static final String PASSWORD = "SafePass1!";
	private static final TokenRequestMetadata METADATA = new TokenRequestMetadata("127.0.0.1", "test-agent");

	private final AccountActivationRepository repository = mock(AccountActivationRepository.class);
	private final OneTimeTokenHasher tokenHasher = new OneTimeTokenHasher();
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
	private final AccountActivationService service = new AccountActivationService(
			repository,
			tokenHasher,
			passwordEncoder,
			3
	);

	@Test
	void activatesManagerAndStoresRequiredConsents() {
		AccountActivationTarget target = target(Role.MANAGER);
		stubResolvable(target, InvitationPurpose.INVITE_OPERATOR_MANAGER);
		when(repository.activateUser(eq(target.userId()), eq(target.rowVersion()), eq("매니저"), any(), any()))
				.thenReturn(true);
		when(repository.markInvitationUsed(eq(target.tokenId()), eq("request-1"), any())).thenReturn(true);

		var response = service.activateManager(
				new ManagerSignupRequest(
						target.userId(),
						" raw-token ",
						" 매니저 ",
						PASSWORD,
						PASSWORD,
						true,
						true
				),
				METADATA,
				"request-1",
				"ko-KR"
		);

		assertThat(response.userId()).isEqualTo(target.userId());
		assertThat(response.email()).isEqualTo(target.email());
		assertThat(response.name()).isEqualTo("매니저");
		assertThat(response.role()).isEqualTo(Role.MANAGER);
		assertThat(response.activated()).isTrue();

		ArgumentCaptor<String> passwordHash = ArgumentCaptor.forClass(String.class);
		verify(repository).activateUser(
				eq(target.userId()),
				eq(target.rowVersion()),
				eq("매니저"),
				passwordHash.capture(),
				any()
		);
		assertThat(passwordEncoder.matches(PASSWORD, passwordHash.getValue())).isTrue();
		List<ConsentRecord> consents = capturedConsents();
		assertThat(consents).extracting(ConsentRecord::consentCode)
				.containsExactly(ConsentCode.TERMS_OF_SERVICE, ConsentCode.PRIVACY_POLICY);
		assertThat(consents).allSatisfy(consent -> {
			assertThat(consent.agreed()).isTrue();
			assertThat(consent.policyVersion()).isEqualTo(3);
			assertThat(consent.captureChannel()).isEqualTo("INVITE_LINK");
			assertThat(consent.sourceIp()).isEqualTo("127.0.0.1");
			assertThat(consent.locale()).isEqualTo("ko-KR");
			assertThat(consent.evidenceHash()).hasSize(64);
		});
		verify(repository, never()).activateTraineeMembership(any(), any(), any());
		verify(repository).markInvitationUsed(eq(target.tokenId()), eq("request-1"), any());
	}

	@Test
	void activatesTraineeMembershipAndStoresOptionalConsentChoice() {
		AccountActivationTarget target = target(Role.TRAINEE);
		stubResolvable(target, InvitationPurpose.INVITE_TRAINEE);
		when(repository.activateUser(eq(target.userId()), eq(target.rowVersion()), eq(target.name()), any(), any()))
				.thenReturn(true);
		when(repository.activateTraineeMembership(eq(target.userId()), eq(target.tokenId()), any()))
				.thenReturn(true);
		when(repository.markInvitationUsed(eq(target.tokenId()), eq("request-2"), any())).thenReturn(true);

		var response = service.activateTrainee(
				new TraineeActivationRequest(
						target.userId(),
						"raw-token",
						PASSWORD,
						PASSWORD,
						true,
						true,
						true,
						true,
						false
				),
				METADATA,
				"request-2",
				"ko-KR"
		);

		assertThat(response.userId()).isEqualTo(target.userId());
		assertThat(response.role()).isEqualTo(Role.TRAINEE);
		List<ConsentRecord> consents = capturedConsents();
		assertThat(consents).hasSize(5);
		assertThat(consents).filteredOn(consent -> consent.consentCode() == ConsentCode.ANONYMIZED_DATA_USAGE)
				.singleElement()
				.satisfies(consent -> assertThat(consent.agreed()).isFalse());
		assertThat(consents).filteredOn(consent -> consent.consentCode() != ConsentCode.ANONYMIZED_DATA_USAGE)
				.allSatisfy(consent -> assertThat(consent.agreed()).isTrue());
		verify(repository).activateTraineeMembership(eq(target.userId()), eq(target.tokenId()), any());
		verify(repository).markInvitationUsed(eq(target.tokenId()), eq("request-2"), any());
	}

	@Test
	void rejectsMissingRequiredConsentBeforeLookingUpInvitation() {
		UUID userId = UUID.randomUUID();

		assertThatThrownBy(() -> service.activateManager(
				new ManagerSignupRequest(userId, "raw-token", "매니저", PASSWORD, PASSWORD, true, false),
				METADATA,
				"request-3",
				"ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.REQUIRED_CONSENT_MISSING);
		});
		verify(repository, never()).findTargetForUpdate(any(), any(), any(), any());
	}

	@Test
	void rejectsPasswordMismatchBeforeLookingUpInvitation() {
		UUID userId = UUID.randomUUID();

		assertThatThrownBy(() -> service.activateManager(
				new ManagerSignupRequest(userId, "raw-token", "매니저", PASSWORD, "OtherPass1!", true, true),
				METADATA,
				"request-4",
				"ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
		});
		verify(repository, never()).findTargetForUpdate(any(), any(), any(), any());
	}

	@Test
	void rejectsTokenThatDoesNotBelongToRequestedUser() {
		UUID userId = UUID.randomUUID();
		String tokenHash = tokenHasher.hash("raw-token");
		when(repository.findTargetForUpdate(
				eq(tokenHash),
				eq(userId),
				eq(InvitationPurpose.INVITE_TRAINEE),
				any()
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.activateTrainee(
				new TraineeActivationRequest(
						userId,
						"raw-token",
						PASSWORD,
						PASSWORD,
						true,
						true,
						true,
						true,
						false
				),
				METADATA,
				"request-5",
				"ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.INVITATION_INVALID);
		});
		verify(repository, never()).activateUser(any(), anyInt(), any(), any(), any());
	}

	private void stubResolvable(AccountActivationTarget target, InvitationPurpose purpose) {
		when(repository.findTargetForUpdate(
				eq(tokenHasher.hash("raw-token")),
				eq(target.userId()),
				eq(purpose),
				any()
		)).thenReturn(Optional.of(target));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private List<ConsentRecord> capturedConsents() {
		ArgumentCaptor<List<ConsentRecord>> captor = ArgumentCaptor.forClass((Class) List.class);
		verify(repository).saveConsentRecords(captor.capture());
		return captor.getValue();
	}

	private AccountActivationTarget target(Role role) {
		return new AccountActivationTarget(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"invitee@example.com",
				"초대 사용자",
				role,
				2
		);
	}
}
