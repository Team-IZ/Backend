package com.bigproject.backend.domain.auth.application;

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
import com.bigproject.backend.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenHasher refreshTokenHasher;

	@Transactional
	public LoginResult login(
			LoginRequest request,
			String origin,
			String loginEntryPath,
			TokenRequestMetadata requestMetadata
	) {
		Optional<AuthUser> candidate = authUserRepository.findByNormalizedEmail(normalizeEmail(request.email()));
		String passwordHash = candidate.map(AuthUser::passwordHash).orElse("");
		if (!passwordEncoder.matches(request.password(), passwordHash) || candidate.isEmpty()) {
			throw invalidCredentials();
		}
		AuthUser user = candidate.get();

		validateAccount(user);
		loginClientValidator.validate(origin, loginEntryPath, user.role());

		String accessToken = jwtProvider.createAccessToken(
				user.email(),
				user.role().name(),
				user.organizationId()
		);
		String refreshToken = jwtProvider.createRefreshToken(
				user.email(),
				user.role().name(),
				user.organizationId()
		);
		Optional<RefreshTokenLineage> previousLineage = refreshTokenRepository.revokeForReplacement(
				user.userId(),
				user.organizationId(),
				Instant.now()
		);
		refreshTokenRepository.save(RefreshToken.issue(
				user.userId(),
				refreshTokenHasher.hash(refreshToken),
				jwtProvider.getExpiration(refreshToken),
				requestMetadata,
				previousLineage
		));

		LoginResponse response = new LoginResponse(
				user.userId(),
				user.email(),
				user.name(),
				user.role(),
				user.organizationId(),
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
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효한 리프레시 토큰이 필요합니다.");
		}

		Instant usedAt = Instant.now();
		RefreshTokenSession tokenSession = refreshTokenRepository.findActiveByTokenHash(
				refreshTokenHasher.hash(refreshToken),
				usedAt
		).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효한 리프레시 토큰이 필요합니다."));

		AuthUser user = authUserRepository.findByNormalizedEmail(normalizeEmail(jwtProvider.getEmail(refreshToken)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효한 리프레시 토큰이 필요합니다."));
		validateAccount(user);

		if (!user.userId().equals(tokenSession.userId())
				|| !user.role().name().equals(jwtProvider.getRole(refreshToken))
				|| !Objects.equals(user.organizationId(), jwtProvider.getOrganizationId(refreshToken))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 정보가 변경되어 다시 로그인해야 합니다.");
		}

		String accessToken = jwtProvider.createAccessToken(
				user.email(),
				user.role().name(),
				user.organizationId()
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

	private void validateAccount(AuthUser user) {
		if (!ACTIVE.equals(user.status()) || !user.emailVerified()
				|| user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 로그인할 수 없는 계정입니다.");
		}

		if (user.role() != Role.SUPER_ADMIN) {
			if (user.organizationId() == null || !ACTIVE.equals(user.organizationStatus())) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "기관 인증 컨텍스트를 발급할 수 없습니다.");
			}
		}
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private ResponseStatusException invalidCredentials() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, LOGIN_FAILURE_MESSAGE);
	}
}
