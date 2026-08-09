package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.auth.domain.LoginCohortRepository;
import com.bigproject.backend.domain.auth.domain.RefreshToken;
import com.bigproject.backend.domain.auth.domain.RefreshTokenLineage;
import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.auth.domain.RefreshTokenSession;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.config.AllowedOriginPolicy;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
	private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";
	private static final String PASSWORD = "safe-password";
	private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final TokenRequestMetadata REQUEST_METADATA = new TokenRequestMetadata(
			"127.0.0.1",
			"test-user-agent"
	);

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
	private final JwtProvider jwtProvider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
	private final LoginClientValidator loginClientValidator = new LoginClientValidator(
			new AllowedOriginPolicy("http://localhost:5173", false)
	);
	private final LoginAttemptThrottle loginAttemptThrottle = new LoginAttemptThrottle(
			5,
			Duration.ofMinutes(1),
			Duration.ofMinutes(15)
	);
	private final LoginCohortRepository loginCohortRepository = mock(LoginCohortRepository.class);
	private final LoginDestinationResolver loginDestinationResolver = new LoginDestinationResolver(loginCohortRepository);
	private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
	private final RefreshTokenHasher refreshTokenHasher = new RefreshTokenHasher();

	@Test
	void issuesRoleBearingAccessTokenAndSeparateRefreshToken() {
		UUID organizationId = UUID.randomUUID();
		AuthUser user = activeUser(Role.OPERATOR, organizationId);
		AuthUserRepository repository = repositoryReturning(user);
		AuthService service = serviceWith(repository);
		Instant beforeLogin = Instant.now();
		when(loginCohortRepository.findLatestNameForOrganization(organizationId))
				.thenReturn(Optional.of("AIVLE 7기"));

		LoginResult result = service.login(
				new LoginRequest(" LEAD@example.com ", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		);

		assertThat(result.response().role()).isEqualTo(Role.OPERATOR);
		assertThat(result.response().organizationId()).isEqualTo(organizationId);
		assertThat(result.response().redirectPath()).isEqualTo("/cohorts/AIVLE%207%EA%B8%B0");
		assertThat(jwtProvider.getRole(result.response().accessToken())).isEqualTo("OPERATOR");
		assertThat(jwtProvider.getOrganizationId(result.response().accessToken())).isEqualTo(organizationId);
		assertThat(jwtProvider.isRefreshToken(result.refreshToken())).isTrue();

		ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(tokenCaptor.capture());
		RefreshToken savedToken = tokenCaptor.getValue();
		assertThat(savedToken.userId()).isEqualTo(user.userId());
		assertThat(savedToken.tokenHash()).isEqualTo(refreshTokenHasher.hash(result.refreshToken()));
		assertThat(savedToken.tokenHash()).isNotEqualTo(result.refreshToken());
		assertThat(savedToken.expiresAt()).isEqualTo(jwtProvider.getExpiration(result.refreshToken()));
		assertThat(savedToken.issuedIp()).isEqualTo("127.0.0.1");
		assertThat(savedToken.issuedUserAgent()).isEqualTo("test-user-agent");
		ArgumentCaptor<Instant> loginAtCaptor = ArgumentCaptor.forClass(Instant.class);
		verify(repository).updateLastLoginAt(eq(user.userId()), loginAtCaptor.capture());
		assertThat(loginAtCaptor.getValue()).isBetween(beforeLogin, Instant.now());
	}

	@Test
	void allowsSuperAdminWithoutOrganizationAndReturnsAdminDestination() {
		AuthService service = serviceReturning(activeUser(Role.SUPER_ADMIN, null));

		LoginResult result = service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		);

		assertThat(result.response().redirectPath()).isEqualTo("/admin/orgs");
		assertThat(jwtProvider.getRole(result.response().accessToken())).isEqualTo("SUPER_ADMIN");
		assertThat(jwtProvider.getOrganizationId(result.response().accessToken())).isNull();
	}

	@Test
	void rejectsManagerWithoutActiveOrganizationContext() {
		AuthUser user = activeUser(Role.MANAGER, null);
		AuthService service = serviceReturning(user);

		assertThatThrownBy(() -> service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		)).isInstanceOfSatisfying(ApiException.class, exception ->
				// 기관 소속이 없는 것은 계정 데이터 결함이다 — 기관이 "정지"된 것과 코드가 갈려야
				// 화면이 재시도를 권할지 관리자 문의를 안내할지 정할 수 있다.
				assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_NO_ORG_CONTEXT));
	}

	@Test
	void returnsSameLoginFailureWhenOnlyEmailIsIncorrect() {
		AuthUserRepository repository = mock(AuthUserRepository.class);
		when(repository.findByNormalizedEmail(any())).thenReturn(Optional.empty());
		AuthService service = serviceWith(repository);

		assertLoginFailure(() -> service.login(
				new LoginRequest("missing@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		));
	}

	@Test
	void returnsSameLoginFailureWhenOnlyPasswordIsIncorrect() {
		AuthService service = serviceReturning(activeUser(Role.MANAGER, UUID.randomUUID()));

		assertLoginFailure(() -> service.login(
				new LoginRequest("lead@example.com", "wrong-password"),
				"http://localhost:5173",
				REQUEST_METADATA
		));
	}

	@Test
	void returnsSameLoginFailureWhenEmailAndPasswordAreIncorrect() {
		AuthUserRepository repository = mock(AuthUserRepository.class);
		when(repository.findByNormalizedEmail(any())).thenReturn(Optional.empty());
		AuthService service = serviceWith(repository);

		assertLoginFailure(() -> service.login(
				new LoginRequest("missing@example.com", "wrong-password"),
				"http://localhost:5173",
				REQUEST_METADATA
		));
	}

	@Test
	void refreshesAccessTokenOnlyWhenRefreshTokenIsStoredAndActive() {
		UUID organizationId = UUID.randomUUID();
		AuthUser user = activeUser(Role.MANAGER, organizationId);
		AuthService service = serviceReturning(user);
		LoginResult loginResult = service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		);
		ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(tokenCaptor.capture());
		RefreshToken storedToken = tokenCaptor.getValue();
		when(refreshTokenRepository.findActiveByTokenHash(eq(storedToken.tokenHash()), any()))
				.thenReturn(Optional.of(new RefreshTokenSession(storedToken.tokenId(), user.userId())));

		var response = service.refresh(
				loginResult.refreshToken(),
				"http://localhost:5173",
				REQUEST_METADATA
		);

		assertThat(jwtProvider.isAccessToken(response.accessToken())).isTrue();
		verify(refreshTokenRepository).updateLastUsed(eq(storedToken.tokenId()), any(), eq(REQUEST_METADATA));
	}

	@Test
	void issuesUniqueRefreshTokensForRepeatedLogins() {
		AuthService service = serviceReturning(activeUser(Role.SUPER_ADMIN, null));

		LoginResult first = service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		);
		LoginResult second = service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		);

		assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
	}

	@Test
	void revokesPreviousTokenBeforeSavingReplacementWithParentLineage() {
		UUID organizationId = UUID.randomUUID();
		AuthUser user = activeUser(Role.OPERATOR, organizationId);
		UUID previousTokenId = UUID.randomUUID();
		UUID tokenFamilyId = UUID.randomUUID();
		when(refreshTokenRepository.revokeForReplacement(eq(user.userId()), eq(organizationId), any()))
				.thenReturn(Optional.of(new RefreshTokenLineage(previousTokenId, tokenFamilyId)));
		AuthService service = serviceReturning(user);

		service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				REQUEST_METADATA
		);

		ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		var ordered = inOrder(refreshTokenRepository);
		ordered.verify(refreshTokenRepository).revokeForReplacement(eq(user.userId()), eq(organizationId), any());
		ordered.verify(refreshTokenRepository).save(tokenCaptor.capture());
		assertThat(tokenCaptor.getValue().parentTokenId()).isEqualTo(previousTokenId);
		assertThat(tokenCaptor.getValue().tokenFamilyId()).isEqualTo(tokenFamilyId);
	}

	@Test
	void revokesCurrentRefreshTokenOnLogout() {
		AuthService service = serviceReturning(activeUser(Role.SUPER_ADMIN, null));

		service.logout("refresh-jwt", "http://localhost:5173");

		verify(refreshTokenRepository).revokeByTokenHash(
				eq(refreshTokenHasher.hash("refresh-jwt")),
				any()
		);
	}

	@Test
	void logoutWithoutCookieRemainsIdempotent() {
		AuthService service = serviceReturning(activeUser(Role.SUPER_ADMIN, null));

		service.logout(null, "http://localhost:5173");

		verify(refreshTokenRepository, never()).revokeByTokenHash(any(), any());
	}

	private AuthService serviceReturning(AuthUser user) {
		return serviceWith(repositoryReturning(user));
	}

	private AuthUserRepository repositoryReturning(AuthUser user) {
		AuthUserRepository repository = mock(AuthUserRepository.class);
		when(repository.findByNormalizedEmail(any())).thenReturn(Optional.of(user));
		return repository;
	}

	private AuthService serviceWith(AuthUserRepository repository) {
		return new AuthService(
				repository,
				passwordEncoder,
				jwtProvider,
				loginClientValidator,
				loginDestinationResolver,
				refreshTokenRepository,
				refreshTokenHasher,
				loginAttemptThrottle
		);
	}

	private void assertLoginFailure(Runnable loginRequest) {
		// 이메일이 없는 경우와 비밀번호가 틀린 경우가 같은 코드로 나가야 한다.
		// 갈라 주면 어떤 이메일이 가입돼 있는지 외부에서 확인할 수 있다(계정 열거).
		assertThatThrownBy(loginRequest::run)
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_INVALID);
					assertThat(exception.errorCode().status().value()).isEqualTo(400);
					assertThat(exception.getMessage()).isEqualTo("로그인을 실패했습니다");
				});
	}

	// ── 로그인 실패 사유가 코드로 갈리는가 ────────────────────────────
	//
	// 전에는 아래 넷이 전부 403 "현재 로그인할 수 없는 계정입니다." 하나로 나갔다.
	// 화면이 해야 할 일은 사유마다 다른데(문의 안내 / 카운트다운 / 기관 문의) 응답에
	// 그 정보가 없어 프론트가 message 문자열을 비교하는 수밖에 없었다.

	@Test
	void 정지된_계정은_기관_정지와_다른_코드로_거절된다() {
		AuthUser suspended = userWith("INACTIVE", true, null, Role.OPERATOR, ORGANIZATION_ID, "ACTIVE");

		assertThatThrownBy(() -> login(serviceReturning(suspended)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_ACCOUNT_INACTIVE));
	}

	@Test
	void 기관이_정지되면_계정_정지와_다른_코드로_거절된다() {
		AuthUser orgSuspended = userWith("ACTIVE", true, null, Role.OPERATOR, ORGANIZATION_ID, "SUSPENDED");

		assertThatThrownBy(() -> login(serviceReturning(orgSuspended)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_ORG_SUSPENDED));
	}

	@Test
	void 일시_차단은_남은_시간을_초로_함께_준다() {
		Instant blockedUntil = Instant.now().plusSeconds(300);
		AuthUser blocked = userWith("ACTIVE", true, blockedUntil, Role.OPERATOR, ORGANIZATION_ID, "ACTIVE");

		assertThatThrownBy(() -> login(serviceReturning(blocked)))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_TEMPORARILY_BLOCKED);
					// 화면이 카운트다운을 하려면 남은 초가 응답에 있어야 한다.
					assertThat(exception.retryAfterSeconds()).isBetween(1L, 300L);
				});
	}

	@Test
	void 차단_시각이_지났으면_로그인을_막지_않는다() {
		AuthUser expired = userWith(
				"ACTIVE", true, Instant.now().minusSeconds(1), Role.OPERATOR, ORGANIZATION_ID, "ACTIVE");

		// login_blocked_until은 상태가 아니라 시각이라, 지나면 별도 해제 없이 그냥 풀린다.
		assertThatCode(() -> login(serviceReturning(expired))).doesNotThrowAnyException();
	}

	@Test
	void 아직_활성화하지_않은_계정은_비밀번호가_없어_로그인_불일치로_끝난다() {
		// PENDING 계정은 password_hash가 NULL이라 비밀번호 검사를 통과하지 못한다.
		// 여기서 "초대를 수락하세요"를 알려 주려면 비밀번호 검사 앞에서 상태를 봐야 하고,
		// 그러면 아무나 이메일 가입 여부를 확인할 수 있게 되므로 의도적으로 합쳐 둔다.
		AuthUser pending = new AuthUser(
				UUID.randomUUID(), ORGANIZATION_ID, "lead@example.com", null, null,
				"PENDING", false, null, Role.TRAINEE, "ACTIVE");

		assertLoginFailure(() -> login(serviceReturning(pending)));
	}

	// ── 연속 실패 차단(A1) ────────────────────────────────────────────
	//
	// 코드(LOGIN_TEMPORARILY_BLOCKED)와 retryAfter는 전부터 있었지만 카운터가 없어
	// 실제로는 한 번도 발생하지 않았다. 임계값은 3차 요청서 A1로 확정됐다.

	@Test
	void 다섯_번_연속_실패하면_60초_동안_거절한다() {
		AuthService service = serviceReturning(activeUser(Role.OPERATOR, ORGANIZATION_ID));

		for (int attempt = 0; attempt < 4; attempt++) {
			// 4회까지는 여전히 "로그인 정보 불일치"다. 오타 반복을 차단으로 처리하면 안 된다.
			assertLoginFailure(() -> loginWithWrongPassword(service));
		}
		assertLoginFailure(() -> loginWithWrongPassword(service));

		assertThatThrownBy(() -> loginWithWrongPassword(service))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_TEMPORARILY_BLOCKED);
					assertThat(exception.retryAfterSeconds()).isBetween(1L, 60L);
				});
	}

	@Test
	void 차단_중에는_올바른_비밀번호도_거절한다() {
		// 비밀번호 검사보다 먼저 봐야 자동화를 실제로 막는다. 뒤에 두면 차단 중에도 매번 대조하게 된다.
		AuthService service = serviceReturning(activeUser(Role.OPERATOR, ORGANIZATION_ID));
		for (int attempt = 0; attempt < 5; attempt++) {
			assertLoginFailure(() -> loginWithWrongPassword(service));
		}

		assertThatThrownBy(() -> login(service))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_TEMPORARILY_BLOCKED));
	}

	@Test
	void 실패가_이어져도_임계값_전이면_막지_않는다() {
		AuthService service = serviceReturning(activeUser(Role.OPERATOR, ORGANIZATION_ID));
		for (int attempt = 0; attempt < 4; attempt++) {
			assertLoginFailure(() -> loginWithWrongPassword(service));
		}

		assertThatCode(() -> login(service)).doesNotThrowAnyException();
	}

	@Test
	void 성공하면_카운터가_0으로_돌아간다() {
		AuthService service = serviceReturning(activeUser(Role.OPERATOR, ORGANIZATION_ID));
		for (int attempt = 0; attempt < 4; attempt++) {
			assertLoginFailure(() -> loginWithWrongPassword(service));
		}

		login(service);

		// 리셋되지 않았다면 다음 실패 한 번으로 5회에 닿아 429가 났을 것이다.
		assertLoginFailure(() -> loginWithWrongPassword(service));
		assertThatCode(() -> login(service)).doesNotThrowAnyException();
	}

	@Test
	void 다른_IP에서의_실패는_남의_로그인을_막지_않는다() {
		// 이메일만으로 세면 남의 이메일에 다섯 번 틀리는 것만으로 그 사람을 막을 수 있다 —
		// 화면정의서 v2가 계정 잠금을 버린 이유가 바로 그것이다.
		AuthService service = serviceReturning(activeUser(Role.OPERATOR, ORGANIZATION_ID));
		TokenRequestMetadata attacker = new TokenRequestMetadata("203.0.113.9", "attacker");
		for (int attempt = 0; attempt < 6; attempt++) {
			assertThatThrownBy(() -> service.login(
					new LoginRequest("lead@example.com", "wrong-password"),
					"http://localhost:5173",
					attacker
			)).isInstanceOf(ApiException.class);
		}

		assertThatCode(() -> login(service)).doesNotThrowAnyException();
	}

	private void login(AuthService service) {
		service.login(new LoginRequest("lead@example.com", PASSWORD), "http://localhost:5173", REQUEST_METADATA);
	}

	private void loginWithWrongPassword(AuthService service) {
		service.login(new LoginRequest("lead@example.com", "wrong-password"), "http://localhost:5173", REQUEST_METADATA);
	}

	private AuthUser userWith(
			String status,
			boolean emailVerified,
			Instant lockedUntil,
			Role role,
			UUID organizationId,
			String organizationStatus
	) {
		return new AuthUser(
				UUID.randomUUID(),
				organizationId,
				"lead@example.com",
				"테스트 사용자",
				passwordEncoder.encode(PASSWORD),
				status,
				emailVerified,
				lockedUntil,
				role,
				organizationStatus
		);
	}

	private AuthUser activeUser(Role role, UUID organizationId) {
		return new AuthUser(
				UUID.randomUUID(),
				organizationId,
				"lead@example.com",
				"테스트 사용자",
				passwordEncoder.encode(PASSWORD),
				"ACTIVE",
				true,
				null,
				role,
				organizationId == null ? null : "ACTIVE"
		);
	}
}
