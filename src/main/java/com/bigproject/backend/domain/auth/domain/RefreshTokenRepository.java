package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
	void save(RefreshToken refreshToken);

	Optional<RefreshTokenLineage> revokeForReplacement(
			UUID userId,
			UUID organizationId,
			Instant revokedAt
	);

	void revokeByTokenHash(String tokenHash, Instant revokedAt);

	/**
	 * 한 사용자의 활성 리프레시 토큰을 <b>관리자 조치로</b> 전부 폐기한다.
	 *
	 * <p>로그인 차단은 <b>새 로그인만</b> 막는다. 이미 로그인해 둔 세션은 리프레시 토큰으로 계속
	 * 연장되므로, 토큰을 함께 끊지 않으면 "차단했는데 그 사람은 계속 쓰고 있는" 상태가 된다 —
	 * 계정 탈취가 의심되는 상황에서 정확히 막아야 하는 것이 그 세션이다.
	 *
	 * <p>폐기 사유를 {@code ROTATED}(로그인 회전)가 아니라 {@code ADMIN_REVOKED}로 남기고
	 * {@code revoked_by}에 조치자를 기록한다. 사유가 같으면 감사에서 정상 회전과 구분되지 않는다.
	 *
	 * @return 폐기한 토큰 수
	 */
	int revokeAllByAdmin(UUID userId, UUID revokedBy, Instant revokedAt);

	Optional<RefreshTokenSession> findActiveByTokenHash(String tokenHash, Instant usedAt);

	void updateLastUsed(UUID tokenId, Instant usedAt, TokenRequestMetadata requestMetadata);
}
