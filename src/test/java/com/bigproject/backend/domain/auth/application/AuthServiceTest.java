package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.auth.domain.RefreshToken;
import com.bigproject.backend.domain.auth.domain.RefreshTokenLineage;
import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.auth.domain.RefreshTokenSession;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
	private static final TokenRequestMetadata REQUEST_METADATA = new TokenRequestMetadata(
			"127.0.0.1",
			"test-user-agent"
	);

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
	private final JwtProvider jwtProvider = new JwtProvider(SECRET, 1_800_000, 604_800_000);
	private final LoginClientValidator loginClientValidator = new LoginClientValidator(
			"http://localhost:5173",
			"/superadmin/login",
			"/manager/login"
	);
	private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
	private final RefreshTokenHasher refreshTokenHasher = new RefreshTokenHasher();

	@Test
	void issuesRoleBearingAccessTokenAndSeparateRefreshToken() {
		UUID organizationId = UUID.randomUUID();
		AuthUser user = activeUser(Role.LEAD_MANAGER, organizationId);
		AuthService service = serviceReturning(user);

		LoginResult result = service.login(
				new LoginRequest(" LEAD@example.com ", PASSWORD),
				"http://localhost:5173",
				"/manager/login",
				REQUEST_METADATA
		);

		assertThat(result.response().role()).isEqualTo(Role.LEAD_MANAGER);
		assertThat(result.response().organizationId()).isEqualTo(organizationId);
		assertThat(jwtProvider.getRole(result.response().accessToken())).isEqualTo("LEAD_MANAGER");
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
	}

	@Test
	void allowsSuperAdminWithoutOrganizationOnSuperAdminPath() {
		AuthService service = serviceReturning(activeUser(Role.SUPER_ADMIN, null));

		LoginResult result = service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				"/superadmin/login",
				REQUEST_METADATA
		);

		assertThat(jwtProvider.getRole(result.response().accessToken())).isEqualTo("SUPER_ADMIN");
		assertThat(jwtProvider.getOrganizationId(result.response().accessToken())).isNull();
	}

	@Test
	void rejectsRoleThatDoesNotMatchLoginPathBeforeIssuingTokens() {
		AuthService service = serviceReturning(activeUser(Role.SUPER_ADMIN, null));

		assertThatThrownBy(() -> service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				"/manager/login",
				REQUEST_METADATA
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("역할과 로그인 진입 경로");
	}

	@Test
	void rejectsManagerWithoutActiveOrganizationContext() {
		AuthUser user = activeUser(Role.MANAGER, null);
		AuthService service = serviceReturning(user);

		assertThatThrownBy(() -> service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				"/manager/login",
				REQUEST_METADATA
		)).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("기관 인증 컨텍스트");
	}

	@Test
	void returnsSameLoginFailureWhenOnlyEmailIsIncorrect() {
		AuthUserRepository repository = normalizedEmail -> Optional.empty();
		AuthService service = serviceWith(repository);

		assertLoginFailure(() -> service.login(
				new LoginRequest("missing@example.com", PASSWORD),
				"http://localhost:5173",
				"/manager/login",
				REQUEST_METADATA
		));
	}

	@Test
	void returnsSameLoginFailureWhenOnlyPasswordIsIncorrect() {
		AuthService service = serviceReturning(activeUser(Role.MANAGER, UUID.randomUUID()));

		assertLoginFailure(() -> service.login(
				new LoginRequest("lead@example.com", "wrong-password"),
				"http://localhost:5173",
				"/manager/login",
				REQUEST_METADATA
		));
	}

	@Test
	void returnsSameLoginFailureWhenEmailAndPasswordAreIncorrect() {
		AuthUserRepository repository = normalizedEmail -> Optional.empty();
		AuthService service = serviceWith(repository);

		assertLoginFailure(() -> service.login(
				new LoginRequest("missing@example.com", "wrong-password"),
				"http://localhost:5173",
				"/manager/login",
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
				"/manager/login",
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
				"/superadmin/login",
				REQUEST_METADATA
		);
		LoginResult second = service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				"/superadmin/login",
				REQUEST_METADATA
		);

		assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
	}

	@Test
	void revokesPreviousTokenBeforeSavingReplacementWithParentLineage() {
		UUID organizationId = UUID.randomUUID();
		AuthUser user = activeUser(Role.LEAD_MANAGER, organizationId);
		UUID previousTokenId = UUID.randomUUID();
		String tokenFamilyId = UUID.randomUUID().toString();
		when(refreshTokenRepository.revokeForReplacement(eq(user.userId()), eq(organizationId), any()))
				.thenReturn(Optional.of(new RefreshTokenLineage(previousTokenId, tokenFamilyId)));
		AuthService service = serviceReturning(user);

		service.login(
				new LoginRequest("lead@example.com", PASSWORD),
				"http://localhost:5173",
				"/manager/login",
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
		AuthUserRepository repository = normalizedEmail -> Optional.of(user);
		return serviceWith(repository);
	}

	private AuthService serviceWith(AuthUserRepository repository) {
		return new AuthService(
				repository,
				passwordEncoder,
				jwtProvider,
				loginClientValidator,
				refreshTokenRepository,
				refreshTokenHasher
		);
	}

	private void assertLoginFailure(Runnable loginRequest) {
		assertThatThrownBy(loginRequest::run)
				.isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
					assertThat(exception.getStatusCode().value()).isEqualTo(400);
					assertThat(exception.getReason()).isEqualTo("로그인을 실패했습니다");
				});
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
