package com.bigproject.backend.domain.auth.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {
	@Modifying(flushAutomatically = true)
	@Query(value = """
			INSERT INTO refresh_token (
				token_id, user_id, token_hash, token_family_id, parent_token_id,
				issued_at, expires_at, last_used_at, revoked_at,
				issued_ip, last_used_ip, issued_user_agent, last_user_agent, reuse_detected_at
			) VALUES (
				:tokenId, :userId, :tokenHash, :tokenFamilyId, :parentTokenId,
				:issuedAt, :expiresAt, NULL, NULL,
				CAST(:issuedIp AS inet), CAST(:issuedIp AS inet), :issuedUserAgent, :issuedUserAgent, NULL
			)
			""", nativeQuery = true)
	int insert(
			@Param("tokenId") UUID tokenId,
			@Param("userId") UUID userId,
			@Param("tokenHash") String tokenHash,
			@Param("tokenFamilyId") UUID tokenFamilyId,
			@Param("parentTokenId") UUID parentTokenId,
			@Param("issuedAt") Instant issuedAt,
			@Param("expiresAt") Instant expiresAt,
			@Param("issuedIp") String issuedIp,
			@Param("issuedUserAgent") String issuedUserAgent
	);

	@Query(value = """
			SELECT token_id AS "tokenId", token_family_id AS "tokenFamilyId"
			FROM refresh_token
			WHERE user_id = :userId
			ORDER BY issued_at DESC, created_at DESC, token_id DESC
			LIMIT 1
			""", nativeQuery = true)
	List<RefreshTokenLineageProjection> findLatestLineage(@Param("userId") UUID userId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE refresh_token
			SET revoked_at = :revokedAt, revoked_reason = 'ROTATED'
			WHERE user_id = :userId
				AND revoked_at IS NULL
			""", nativeQuery = true)
	int revokeActiveByUser(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE refresh_token
			SET revoked_at = :revokedAt, revoked_reason = 'LOGOUT'
			WHERE token_hash = :tokenHash
				AND revoked_at IS NULL
			""", nativeQuery = true)
	int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("revokedAt") Instant revokedAt);

	@Query(value = """
			SELECT current_token.token_id AS "tokenId", current_token.user_id AS "userId"
			FROM refresh_token current_token
			WHERE current_token.token_hash = :tokenHash
				AND current_token.revoked_at IS NULL
				AND current_token.reuse_detected_at IS NULL
				AND current_token.expires_at > :usedAt
				AND NOT EXISTS (
					SELECT 1
					FROM refresh_token child_token
					WHERE child_token.parent_token_id = current_token.token_id
				)
			""", nativeQuery = true)
	Optional<RefreshTokenSessionProjection> findActiveSession(
			@Param("tokenHash") String tokenHash,
			@Param("usedAt") Instant usedAt
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE refresh_token
			SET last_used_at = :usedAt,
				last_used_ip = CAST(:lastUsedIp AS inet),
				last_user_agent = :lastUserAgent
			WHERE token_id = :tokenId
				AND revoked_at IS NULL
			""", nativeQuery = true)
	int updateLastUsed(
			@Param("tokenId") UUID tokenId,
			@Param("usedAt") Instant usedAt,
			@Param("lastUsedIp") String lastUsedIp,
			@Param("lastUserAgent") String lastUserAgent
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE refresh_token
			SET revoked_at = :revokedAt, revoked_reason = 'PASSWORD_CHANGED'
			WHERE user_id = :userId AND revoked_at IS NULL
			""", nativeQuery = true)
	int revokeAllForPasswordChange(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);

	interface RefreshTokenLineageProjection {
		UUID getTokenId();
		UUID getTokenFamilyId();
	}

	interface RefreshTokenSessionProjection {
		UUID getTokenId();
		UUID getUserId();
	}
}
