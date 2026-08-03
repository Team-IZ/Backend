package com.bigproject.backend.domain.auth.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OneTimeTokenJpaRepository extends JpaRepository<OneTimeTokenJpaEntity, UUID> {
	@Query(value = """
			SELECT
				ott.token_id AS "tokenId",
				u.user_id AS "userId",
				u.org_id AS "organizationId",
				CAST(u.email AS text) AS "email",
				u.name AS "name",
				u.row_version AS "rowVersion",
				r.code AS "roleCode"
			FROM one_time_token ott
			JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
			JOIN app_user u ON u.user_id = ott.user_id
			JOIN "role" r ON r.role_id = u.role_id
			JOIN organization o ON o.org_id = ott.org_id
			WHERE ott.token_hash = :tokenHash
				AND ott.user_id = :userId
				AND ott.purpose = :purpose
				AND ui.current_token_id = ott.token_id
				AND ui.status = 'SENT'
				AND ott.used_at IS NULL
				AND ott.invalidated_at IS NULL
				AND ott.expires_at > :activatedAt
				AND u.status = 'PENDING'
				AND u.deleted_at IS NULL
				AND u.org_id = ott.org_id
				AND u.normalized_email = ott.target_email_normalized
				AND o.status = 'ACTIVE'
				AND o.deleted_at IS NULL
			FOR UPDATE OF ott, ui, u, o
			""", nativeQuery = true)
	Optional<AccountActivationProjection> findActivationTargetForUpdate(
			@Param("tokenHash") String tokenHash,
			@Param("userId") UUID userId,
			@Param("purpose") String purpose,
			@Param("activatedAt") Instant activatedAt
	);

	@Query(value = """
			SELECT u.user_id AS "userId", CAST(u.email AS text) AS "email"
			FROM one_time_token ott
			JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
			JOIN app_user u ON u.user_id = ott.user_id
			JOIN organization o ON o.org_id = ott.org_id
			WHERE ott.token_hash = :tokenHash
				AND ott.purpose IN ('INVITE_OPERATOR_MANAGER', 'INVITE_TRAINEE')
				AND ui.current_token_id = ott.token_id
				AND ui.status = 'SENT'
				AND ott.used_at IS NULL
				AND ott.invalidated_at IS NULL
				AND ott.expires_at > :resolvedAt
				AND u.status = 'PENDING'
				AND u.deleted_at IS NULL
				AND u.org_id = ott.org_id
				AND u.normalized_email = ott.target_email_normalized
				AND o.status = 'ACTIVE'
				AND o.deleted_at IS NULL
			""", nativeQuery = true)
	Optional<InvitationRecipientProjection> findResolvableInvitation(
			@Param("tokenHash") String tokenHash,
			@Param("resolvedAt") Instant resolvedAt
	);

	@Query(value = """
			SELECT
				ott.token_id AS "tokenId",
				ott.user_id AS "userId",
				ott.org_id AS "organizationId",
				ott.target_email AS "email",
				ott.target_email_normalized AS "normalizedEmail",
				u.password_hash AS "passwordHash",
				u.status AS "userStatus",
				ott.purpose AS "purpose",
				ott.expires_at AS "expiresAt",
				ott.used_at AS "usedAt",
				ott.invalidated_at AS "invalidatedAt"
			FROM one_time_token ott
			LEFT JOIN app_user u ON u.user_id = ott.user_id AND u.deleted_at IS NULL
			WHERE ott.token_hash = :tokenHash
			FOR UPDATE OF ott
			""", nativeQuery = true)
	Optional<PasswordResetTokenProjection> findPasswordResetTokenForUpdate(@Param("tokenHash") String tokenHash);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			INSERT INTO one_time_token (
				token_id, org_id, user_id, invitation_id, target_email, target_email_normalized,
				purpose, token_hash, payload, issued_at, expires_at, used_at,
				invalidated_at, invalidated_reason, replaced_by_token_id,
				issued_by, issued_request_id, used_request_id, created_at
			) VALUES (
				:tokenId, :organizationId, :userId, :invitationId, :email, :normalizedEmail,
				:purpose, :tokenHash, CAST(:payload AS jsonb), :issuedAt, :expiresAt, NULL,
				NULL, NULL, NULL, NULL, :requestId, NULL, :issuedAt
			)
			""", nativeQuery = true)
	int insert(
			@Param("tokenId") UUID tokenId,
			@Param("organizationId") UUID organizationId,
			@Param("userId") UUID userId,
			@Param("invitationId") UUID invitationId,
			@Param("email") String email,
			@Param("normalizedEmail") String normalizedEmail,
			@Param("purpose") String purpose,
			@Param("tokenHash") String tokenHash,
			@Param("payload") String payload,
			@Param("issuedAt") Instant issuedAt,
			@Param("expiresAt") Instant expiresAt,
			@Param("requestId") String requestId
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE one_time_token
			SET invalidated_at = :invalidatedAt,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = :replacementTokenId
			WHERE user_id = :userId
				AND purpose = 'PASSWORD_RESET'
				AND token_id <> :replacementTokenId
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""", nativeQuery = true)
	int invalidatePreviousResetTokens(
			@Param("userId") UUID userId,
			@Param("replacementTokenId") UUID replacementTokenId,
			@Param("invalidatedAt") Instant invalidatedAt
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE one_time_token
			SET invalidated_at = :invalidatedAt,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = :replacementTokenId
			WHERE invitation_id = :invitationId
				AND token_id <> :replacementTokenId
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""", nativeQuery = true)
	int invalidatePreviousInvitationTokens(
			@Param("invitationId") UUID invitationId,
			@Param("replacementTokenId") UUID replacementTokenId,
			@Param("invalidatedAt") Instant invalidatedAt
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE one_time_token
			SET used_at = :usedAt, used_request_id = :requestId
			WHERE token_id = :tokenId
				AND purpose = 'PASSWORD_RESET'
				AND used_at IS NULL
				AND invalidated_at IS NULL
				AND expires_at > :usedAt
			""", nativeQuery = true)
	int markPasswordResetTokenUsed(
			@Param("tokenId") UUID tokenId,
			@Param("requestId") String requestId,
			@Param("usedAt") Instant usedAt
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE one_time_token
			SET used_at = :usedAt, used_request_id = :requestId
			WHERE token_id = :tokenId
				AND used_at IS NULL
				AND invalidated_at IS NULL
				AND expires_at > :usedAt
			""", nativeQuery = true)
	int markInvitationTokenUsed(
			@Param("tokenId") UUID tokenId,
			@Param("requestId") String requestId,
			@Param("usedAt") Instant usedAt
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE one_time_token
			SET invalidated_at = :invalidatedAt,
				invalidated_reason = 'PASSWORD_CHANGED'
			WHERE user_id = :userId
				AND purpose = 'PASSWORD_RESET'
				AND token_id <> :usedTokenId
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""", nativeQuery = true)
	int invalidateOtherResetTokens(
			@Param("userId") UUID userId,
			@Param("usedTokenId") UUID usedTokenId,
			@Param("invalidatedAt") Instant invalidatedAt
	);

	interface AccountActivationProjection {
		UUID getTokenId();
		UUID getUserId();
		UUID getOrganizationId();
		String getEmail();
		String getName();
		String getRoleCode();
		int getRowVersion();
	}

	interface InvitationRecipientProjection {
		UUID getUserId();
		String getEmail();
	}

	interface PasswordResetTokenProjection {
		UUID getTokenId();
		UUID getUserId();
		UUID getOrganizationId();
		String getEmail();
		String getNormalizedEmail();
		String getPasswordHash();
		String getUserStatus();
		String getPurpose();
		Instant getExpiresAt();
		Instant getUsedAt();
		Instant getInvalidatedAt();
	}
}
