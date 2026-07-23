package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.RefreshToken;
import com.bigproject.backend.domain.auth.domain.RefreshTokenLineage;
import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.auth.domain.RefreshTokenSession;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {
	private static final String INSERT = """
			INSERT INTO refresh_token (
				token_id,
				user_id,
				token_hash,
				token_family_id,
				parent_token_id,
				issued_at,
				expires_at,
				last_used_at,
				revoked_at,
				issued_ip,
				last_used_ip,
				issued_user_agent,
				last_user_agent,
				reuse_detected_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, CAST(? AS inet), CAST(? AS inet), ?, ?, NULL)
			""";
	private static final String LOCK_USER_CONTEXT = """
			SELECT user_id
			FROM app_user
			WHERE user_id = ?
				AND org_id IS NOT DISTINCT FROM ?
			FOR UPDATE
			""";
	private static final String FIND_LATEST_LINEAGE = """
			SELECT token_id, token_family_id
			FROM refresh_token
			WHERE user_id = ?
			ORDER BY issued_at DESC, created_at DESC, token_id DESC
			LIMIT 1
			""";
	private static final String REVOKE_ACTIVE_BY_USER = """
			UPDATE refresh_token
			SET revoked_at = ?
			WHERE user_id = ?
				AND revoked_at IS NULL
			""";
	private static final String REVOKE_BY_TOKEN_HASH = """
			UPDATE refresh_token
			SET revoked_at = ?
			WHERE token_hash = ?
				AND revoked_at IS NULL
			""";
	private static final String FIND_ACTIVE_BY_TOKEN_HASH = """
			SELECT current_token.token_id, current_token.user_id
			FROM refresh_token current_token
			WHERE current_token.token_hash = ?
				AND current_token.revoked_at IS NULL
				AND current_token.reuse_detected_at IS NULL
				AND current_token.expires_at > ?
				AND NOT EXISTS (
					SELECT 1
					FROM refresh_token child_token
					WHERE child_token.parent_token_id = current_token.token_id
				)
			""";
	private static final String UPDATE_LAST_USED = """
			UPDATE refresh_token
			SET last_used_at = ?,
				last_used_ip = CAST(? AS inet),
				last_user_agent = ?
			WHERE token_id = ?
				AND revoked_at IS NULL
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void save(RefreshToken refreshToken) {
		jdbcTemplate.update(
				INSERT,
				refreshToken.tokenId(),
				refreshToken.userId(),
				refreshToken.tokenHash(),
				refreshToken.tokenFamilyId(),
				refreshToken.parentTokenId(),
				Timestamp.from(refreshToken.issuedAt()),
				Timestamp.from(refreshToken.expiresAt()),
				refreshToken.issuedIp(),
				refreshToken.issuedIp(),
				refreshToken.issuedUserAgent(),
				refreshToken.issuedUserAgent()
		);
	}

	@Override
	public Optional<RefreshTokenLineage> revokeForReplacement(
			UUID userId,
			UUID organizationId,
			Instant revokedAt
	) {
		jdbcTemplate.queryForObject(LOCK_USER_CONTEXT, UUID.class, userId, organizationId);
		Optional<RefreshTokenLineage> latestLineage = jdbcTemplate.query(
				FIND_LATEST_LINEAGE,
				(rs, rowNum) -> new RefreshTokenLineage(
						rs.getObject("token_id", UUID.class),
						rs.getString("token_family_id")
				),
				userId
		).stream().findFirst();
		jdbcTemplate.update(REVOKE_ACTIVE_BY_USER, Timestamp.from(revokedAt), userId);
		return latestLineage;
	}

	@Override
	public void revokeByTokenHash(String tokenHash, Instant revokedAt) {
		jdbcTemplate.update(REVOKE_BY_TOKEN_HASH, Timestamp.from(revokedAt), tokenHash);
	}

	@Override
	public Optional<RefreshTokenSession> findActiveByTokenHash(String tokenHash, Instant usedAt) {
		return jdbcTemplate.query(
				FIND_ACTIVE_BY_TOKEN_HASH,
				(rs, rowNum) -> new RefreshTokenSession(
						rs.getObject("token_id", UUID.class),
						rs.getObject("user_id", UUID.class)
				),
				tokenHash,
				Timestamp.from(usedAt)
		).stream().findFirst();
	}

	@Override
	public void updateLastUsed(UUID tokenId, Instant usedAt, TokenRequestMetadata requestMetadata) {
		jdbcTemplate.update(
				UPDATE_LAST_USED,
				Timestamp.from(usedAt),
				requestMetadata.ipAddress(),
				requestMetadata.userAgent(),
				tokenId
		);
	}
}
