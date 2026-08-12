package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AccountActivationRepository;
import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.auth.domain.ConsentCode;
import com.bigproject.backend.domain.auth.domain.ConsentRecord;
import com.bigproject.backend.domain.auth.domain.InvitationState;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bigproject.backend.domain.auth.application.InvitationStateFixture.EMAIL;
import static com.bigproject.backend.domain.auth.application.InvitationStateFixture.NAME;
import static com.bigproject.backend.domain.auth.application.InvitationStateFixture.ROW_VERSION;
import static com.bigproject.backend.domain.auth.application.InvitationStateFixture.USER_ID;
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
		InvitationState state = stub(InvitationStateFixture.staff(Role.MANAGER));
		when(repository.activateUser(eq(USER_ID), eq(ROW_VERSION), eq("매니저"), any(), any())).thenReturn(true);
		when(repository.markInvitationUsed(eq(state.tokenId()), eq("request-1"), any())).thenReturn(true);

		var response = service.activateManager(managerSignup(USER_ID), METADATA, "request-1", "ko-KR");

		assertThat(response.userId()).isEqualTo(USER_ID);
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.name()).isEqualTo("매니저");
		assertThat(response.role()).isEqualTo(Role.MANAGER);
		assertThat(response.activated()).isTrue();

		ArgumentCaptor<String> passwordHash = ArgumentCaptor.forClass(String.class);
		verify(repository).activateUser(eq(USER_ID), eq(ROW_VERSION), eq("매니저"), passwordHash.capture(), any());
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
		verify(repository).markInvitationUsed(eq(state.tokenId()), eq("request-1"), any());
	}

	@Test
	void activatesTraineeMembershipAndStoresOptionalConsentChoice() {
		InvitationState state = stub(InvitationStateFixture.valid());
		when(repository.activateUser(eq(USER_ID), eq(ROW_VERSION), eq(NAME), any(), any())).thenReturn(true);
		when(repository.activateTraineeMembership(eq(USER_ID), eq(state.tokenId()), any())).thenReturn(true);
		when(repository.markInvitationUsed(eq(state.tokenId()), eq("request-2"), any())).thenReturn(true);

		var response = service.activateTrainee(traineeActivation(USER_ID), METADATA, "request-2", "ko-KR");

		assertThat(response.userId()).isEqualTo(USER_ID);
		assertThat(response.role()).isEqualTo(Role.TRAINEE);
		List<ConsentRecord> consents = capturedConsents();
		assertThat(consents).hasSize(5);
		assertThat(consents).filteredOn(consent -> consent.consentCode() == ConsentCode.ANONYMIZED_DATA_USAGE)
				.singleElement()
				.satisfies(consent -> assertThat(consent.agreed()).isFalse());
		assertThat(consents).filteredOn(consent -> consent.consentCode() != ConsentCode.ANONYMIZED_DATA_USAGE)
				.allSatisfy(consent -> assertThat(consent.agreed()).isTrue());
		verify(repository).activateTraineeMembership(eq(USER_ID), eq(state.tokenId()), any());
		verify(repository).markInvitationUsed(eq(state.tokenId()), eq("request-2"), any());
	}

	@Test
	void rejectsMissingRequiredConsentBeforeLookingUpInvitation() {
		assertThatThrownBy(() -> service.activateManager(
				new ManagerSignupRequest(USER_ID, "raw-token", "매니저", PASSWORD, PASSWORD, true, false),
				METADATA,
				"request-3",
				"ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.REQUIRED_CONSENT_MISSING));
		verify(repository, never()).findStateForUpdate(any());
	}

	@Test
	void rejectsPasswordMismatchBeforeLookingUpInvitation() {
		assertThatThrownBy(() -> service.activateManager(
				new ManagerSignupRequest(USER_ID, "raw-token", "매니저", PASSWORD, "OtherPass1!", true, true),
				METADATA,
				"request-4",
				"ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH));
		verify(repository, never()).findStateForUpdate(any());
	}

	@Test
	void rejectsTokenThatDoesNotBelongToRequestedUser() {
		stub(InvitationStateFixture.valid().withUserId(UUID.randomUUID()));

		assertThatTraineeActivationFails(AuthErrorCode.INVITATION_INVALID);
	}

	@Test
	void rejectsUnknownToken() {
		when(repository.findStateForUpdate(eq(tokenHasher.hash("raw-token")))).thenReturn(Optional.empty());

		assertThatTraineeActivationFails(AuthErrorCode.INVITATION_INVALID);
	}

	/**
	 * 명단에서 빠진 교육생은 <b>비밀번호를 쓰기 전에</b> 막는다. 전에는 멤버십 갱신 단계까지 가서
	 * {@code ACTIVATION_STATE_CHANGED}("다시 시도해 주세요")로 나갔는데, 다시 시도해도 결과가
	 * 같은 상황이라 화면이 잘못된 안내를 하게 됐다.
	 */
	@Test
	void rejectsTraineeMissingFromRosterBeforeWritingPassword() {
		stub(InvitationStateFixture.valid().withOnRoster(false));

		assertThatTraineeActivationFails(AuthErrorCode.INVITATION_NOT_IN_ROSTER);
		verify(repository, never()).activateUser(any(), anyInt(), any(), any(), any());
		verify(repository, never()).activateTraineeMembership(any(), any(), any());
	}

	@Test
	void reportsExpiredInvitationOnActivation() {
		stub(InvitationStateFixture.valid().withExpiresAt(Instant.now().minus(Duration.ofHours(1))));

		assertThatTraineeActivationFails(AuthErrorCode.INVITATION_EXPIRED);
		verify(repository, never()).activateUser(any(), anyInt(), any(), any(), any());
	}

	@Test
	void reportsAlreadyAcceptedInvitationOnActivation() {
		stub(InvitationStateFixture.valid().withUsedAt(Instant.now()));

		assertThatTraineeActivationFails(AuthErrorCode.INVITATION_ALREADY_ACCEPTED);
		verify(repository, never()).activateUser(any(), anyInt(), any(), any(), any());
	}

	/**
	 * 오퍼레이터·매니저 초대 토큰으로 슈퍼어드민이 되는 권한 상승 경로를 막는다.
	 * 목적과 역할을 교차 허용하면 안 된다.
	 */
	@Test
	void rejectsSuperAdminRoleOnOperatorManagerInvitation() {
		stub(InvitationStateFixture.staff(Role.SUPER_ADMIN));

		assertThatThrownBy(() -> service.activateManager(
				managerSignup(USER_ID), METADATA, "request-6", "ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.INVITATION_INVALID));
		verify(repository, never()).activateUser(any(), anyInt(), any(), any(), any());
	}

	@Test
	void rejectsSuperAdminInvitationUsedForManagerRole() {
		stub(InvitationStateFixture.staff(Role.MANAGER).withPurpose(InvitationPurpose.INVITE_SUPER_ADMIN));

		assertThatThrownBy(() -> service.activateManager(
				managerSignup(USER_ID), METADATA, "request-7", "ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.INVITATION_INVALID));
		verify(repository, never()).activateUser(any(), anyInt(), any(), any(), any());
	}

	/** 교육생 활성화에 운영 계정 초대 토큰을 쓰면 상태를 알려 주지 않고 무효로 막는다. */
	@Test
	void rejectsStaffInvitationOnTraineeActivation() {
		stub(InvitationStateFixture.staff(Role.MANAGER));

		assertThatTraineeActivationFails(AuthErrorCode.INVITATION_INVALID);
	}

	private void assertThatTraineeActivationFails(AuthErrorCode expected) {
		assertThatThrownBy(() -> service.activateTrainee(
				traineeActivation(USER_ID), METADATA, "request-5", "ko-KR"
		)).isInstanceOfSatisfying(ApiException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected));
	}

	private ManagerSignupRequest managerSignup(UUID userId) {
		return new ManagerSignupRequest(userId, " raw-token ", " 매니저 ", PASSWORD, PASSWORD, true, true);
	}

	private TraineeActivationRequest traineeActivation(UUID userId) {
		return new TraineeActivationRequest(userId, "raw-token", PASSWORD, PASSWORD, true, true, true, true, false);
	}

	private InvitationState stub(InvitationStateFixture fixture) {
		InvitationState state = fixture.build();
		when(repository.findStateForUpdate(eq(tokenHasher.hash("raw-token")))).thenReturn(Optional.of(state));
		return state;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private List<ConsentRecord> capturedConsents() {
		ArgumentCaptor<List<ConsentRecord>> captor = ArgumentCaptor.forClass((Class) List.class);
		verify(repository).saveConsentRecords(captor.capture());
		return captor.getValue();
	}
}
