package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.RefreshToken;
import com.bigproject.backend.domain.auth.domain.RefreshTokenLineage;
import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.auth.domain.RefreshTokenSession;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.RefreshTokenJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaRefreshTokenRepository implements RefreshTokenRepository {
	private final AuthUserJpaRepository userRepository;
	private final RefreshTokenJpaRepository tokenRepository;

	@Override
	public void save(RefreshToken refreshToken) {
		if (tokenRepository.insert(
				refreshToken.tokenId(),
				refreshToken.userId(),
				refreshToken.tokenHash(),
				refreshToken.tokenFamilyId(),
				refreshToken.parentTokenId(),
				refreshToken.issuedAt(),
				refreshToken.expiresAt(),
				refreshToken.issuedIp(),
				refreshToken.issuedUserAgent()
		) != 1) {
			throw new IllegalStateException("리프레시 토큰을 저장할 수 없습니다.");
		}
	}

	@Override
	public Optional<RefreshTokenLineage> revokeForReplacement(
			UUID userId,
			UUID organizationId,
			Instant revokedAt
	) {
		userRepository.lockUserContext(userId, organizationId)
				.orElseThrow(() -> new IllegalStateException("로그인 사용자의 토큰 컨텍스트를 잠글 수 없습니다."));
		Optional<RefreshTokenLineage> latestLineage = tokenRepository.findLatestLineage(userId)
				.stream()
				.findFirst()
				.map(row -> new RefreshTokenLineage(row.getTokenId(), row.getTokenFamilyId()));
		tokenRepository.revokeActiveByUser(userId, revokedAt);
		return latestLineage;
	}

	@Override
	public void revokeByTokenHash(String tokenHash, Instant revokedAt) {
		tokenRepository.revokeByTokenHash(tokenHash, revokedAt);
	}

	@Override
	public int revokeAllByAdmin(UUID userId, UUID revokedBy, Instant revokedAt) {
		return tokenRepository.revokeActiveByAdmin(userId, revokedBy, revokedAt);
	}

	@Override
	public Optional<RefreshTokenSession> findActiveByTokenHash(String tokenHash, Instant usedAt) {
		return tokenRepository.findActiveSession(tokenHash, usedAt)
				.map(row -> new RefreshTokenSession(row.getTokenId(), row.getUserId()));
	}

	@Override
	public void updateLastUsed(UUID tokenId, Instant usedAt, TokenRequestMetadata requestMetadata) {
		tokenRepository.updateLastUsed(
				tokenId,
				usedAt,
				requestMetadata.ipAddress(),
				requestMetadata.userAgent()
		);
	}
}
