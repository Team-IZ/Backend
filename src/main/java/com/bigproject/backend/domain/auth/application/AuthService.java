package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.auth.domain.RefreshToken;
import com.bigproject.backend.domain.auth.domain.RefreshTokenLineage;
import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.auth.domain.RefreshTokenSession;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.LoginRequest;
import com.bigproject.backend.domain.auth.presentation.dto.LoginResponse;
import com.bigproject.backend.domain.auth.presentation.dto.RefreshTokenResponse;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {
	private static final String ACTIVE = "ACTIVE";
	private static final String LOGIN_FAILURE_MESSAGE = "로그인을 실패했습니다";

	private final AuthUserRepository authUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtProvider jwtProvider;
	private final LoginClientValidator loginClientValidator;
	private final LoginDestinationResolver loginDestinationResolver;
	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenHasher refreshTokenHasher;
	private final LoginAttemptThrottle loginAttemptThrottle;

	@Transactional
	public LoginResult login(
			LoginRequest request,
			String origin,
			TokenRequestMetadata requestMetadata
	) {
		String normalizedEmail = normalizeEmail(request.email());
		String ipAddress = requestMetadata.ipAddress();
		// 비밀번호 검사보다 먼저 본다. 뒤에 두면 차단 중에도 매번 비밀번호를 대조하게 되어
		// 자동화를 실제로 막지 못한다.
		loginAttemptThrottle.checkNotBlocked(normalizedEmail, ipAddress);

		Optional<AuthUser> candidate = authUserRepository.findByNormalizedEmail(normalizedEmail);
		String passwordHash = candidate.map(AuthUser::passwordHash).orElse("");
		if (!passwordEncoder.matches(request.password(), passwordHash) || candidate.isEmpty()) {
			// 자격 증명이 틀린 경우만 센다. 정지 계정·기관 정지는 재시도해도 결과가 같아
			// 세어 봐야 정상 사용자만 더 막는다.
			loginAttemptThrottle.recordFailure(normalizedEmail, ipAddress);
			throw invalidCredentials();
		}
		// 자격 증명이 맞았다 — 이 자리의 카운터는 여기서 버린다. 뒤의 계정 상태 검사에서 걸리더라도
		// 그건 "연속 실패"가 아니다.
		loginAttemptThrottle.reset(normalizedEmail, ipAddress);
		AuthUser user = candidate.get();

		validateAccount(user);
		loginClientValidator.validateOrigin(origin);
		String redirectPath = loginDestinationResolver.resolveRedirectPath(user);

		String accessToken = jwtProvider.createAccessToken(
				user.email(),
				user.role().name(),
				user.organizationId(),
				user.passwordChangedAt()
		);
		String refreshToken = jwtProvider.createRefreshToken(
				user.email(),
				user.role().name(),
				user.organizationId(),
				user.passwordChangedAt()
		);
		Instant loggedInAt = Instant.now();
		Optional<RefreshTokenLineage> previousLineage = refreshTokenRepository.revokeForReplacement(
				user.userId(),
				user.organizationId(),
				loggedInAt
		);
		refreshTokenRepository.save(RefreshToken.issue(
				user.userId(),
				refreshTokenHasher.hash(refreshToken),
				jwtProvider.getExpiration(refreshToken),
				requestMetadata,
				previousLineage
		));
		authUserRepository.updateLastLoginAt(user.userId(), loggedInAt);

		LoginResponse response = new LoginResponse(
				user.userId(),
				user.email(),
				user.name(),
				user.role(),
				user.organizationId(),
				redirectPath,
				accessToken,
				jwtProvider.getAccessTokenExpiration()
		);
		return new LoginResult(response, refreshToken);
	}

	@Transactional
	public RefreshTokenResponse refresh(
			String refreshToken,
			String origin,
			TokenRequestMetadata requestMetadata
	) {
		loginClientValidator.validateOrigin(origin);
		if (refreshToken == null || !jwtProvider.isRefreshToken(refreshToken)) {
			throw new ApiException(AuthErrorCode.REFRESH_TOKEN_INVALID);
		}

		Instant usedAt = Instant.now();
		RefreshTokenSession tokenSession = refreshTokenRepository.findActiveByTokenHash(
				refreshTokenHasher.hash(refreshToken),
				usedAt
		).orElseThrow(() -> new ApiException(AuthErrorCode.REFRESH_TOKEN_INVALID));

		AuthUser user = authUserRepository.findByNormalizedEmail(normalizeEmail(jwtProvider.getEmail(refreshToken)))
				.orElseThrow(() -> new ApiException(AuthErrorCode.REFRESH_TOKEN_INVALID));
		validateAccount(user);

		if (!user.userId().equals(tokenSession.userId())
				|| !user.role().name().equals(jwtProvider.getRole(refreshToken))
				|| !Objects.equals(user.organizationId(), jwtProvider.getOrganizationId(refreshToken))) {
			throw new ApiException(AuthErrorCode.REFRESH_IDENTITY_CHANGED);
		}

		String accessToken = jwtProvider.createAccessToken(
				user.email(),
				user.role().name(),
				user.organizationId(),
				user.passwordChangedAt()
		);
		refreshTokenRepository.updateLastUsed(tokenSession.tokenId(), usedAt, requestMetadata);
		return new RefreshTokenResponse(accessToken, jwtProvider.getAccessTokenExpiration());
	}

	@Transactional
	public void logout(String refreshToken, String origin) {
		loginClientValidator.validateOrigin(origin);
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}
		refreshTokenRepository.revokeByTokenHash(refreshTokenHasher.hash(refreshToken), Instant.now());
	}

	/**
	 * 로그인할 수 없는 계정을 <b>사유별로</b> 거절한다.
	 *
	 * <p>전에는 넷을 한 덩어리로 묶어 {@code 403 "현재 로그인할 수 없는 계정입니다."} 하나로 냈다.
	 * 화면이 해야 할 일은 사유마다 다른데(정지 계정은 문의 안내, 일시 차단은 남은 시간 카운트다운,
	 * 기관 정지는 또 다른 문구) 응답에 그 정보가 없어서 프론트가 {@code message} 문자열을 비교하는
	 * 수밖에 없었다. 문자열은 계약이 아니라 문구를 다듬는 순간 화면이 조용히 깨진다.
	 *
	 * <p><b>계정 열거로 이어지지 않는다.</b> 이 메서드는 비밀번호 검사를 <b>통과한 뒤에만</b> 불린다 —
	 * 비밀번호를 이미 아는 사람에게 계정 상태를 알려 주는 것이라 새로 새는 정보가 없다.
	 * 아직 활성화하지 않은 계정(status=PENDING)은 비밀번호 자체가 없어 여기까지 오지 못하고
	 * {@code LOGIN_INVALID}로 끝난다 — 그쪽을 구분해 주려면 비밀번호 검사 앞에서 상태를 봐야 하고,
	 * 그러면 아무나 이메일 가입 여부를 확인할 수 있게 된다.
	 */
	private void validateAccount(AuthUser user) {
		Instant now = Instant.now();
		if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
			// 남은 시간을 초로 준다. 0이 되지 않도록 올림한다 — 0을 주면 화면이 즉시 재시도해 또 막힌다.
			long retryAfterSeconds = Math.max(1, Duration.between(now, user.lockedUntil()).toSeconds());
			throw new ApiException(AuthErrorCode.LOGIN_TEMPORARILY_BLOCKED, retryAfterSeconds);
		}
		if (!ACTIVE.equals(user.status()) || !user.emailVerified()) {
			throw new ApiException(AuthErrorCode.LOGIN_ACCOUNT_INACTIVE);
		}

		if (user.role() != Role.SUPER_ADMIN) {
			// 기관이 없는 것은 계정 데이터 결함(5xx)이고, 기관이 정지된 것은 정상 운영 상태(4xx)다.
			if (user.organizationId() == null) {
				throw new ApiException(AuthErrorCode.LOGIN_NO_ORG_CONTEXT);
			}
			if (!ACTIVE.equals(user.organizationStatus())) {
				throw new ApiException(AuthErrorCode.LOGIN_ORG_SUSPENDED);
			}
		}
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private ApiException invalidCredentials() {
		return new ApiException(AuthErrorCode.LOGIN_INVALID, LOGIN_FAILURE_MESSAGE);
	}
}
