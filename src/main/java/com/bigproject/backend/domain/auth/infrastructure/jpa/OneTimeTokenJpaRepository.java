package com.bigproject.backend.domain.auth.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OneTimeTokenJpaRepository extends JpaRepository<OneTimeTokenJpaEntity, UUID> {
	/**
	 * 활성화 대상을 잠금 조회한다. <b>기관 소속을 전제하지 않는다</b> — 슈퍼어드민은 어느 기관에도
	 * 속하지 않아 {@code org_id}가 NULL 이다.
	 *
	 * <ul>
	 *   <li>{@code LEFT JOIN organization} — 기관이 없어도 행이 살아남는다. INNER JOIN 이면
	 *       슈퍼어드민 초대는 결과가 항상 0건이라 수락이 400 으로 막힌다.</li>
	 *   <li>{@code IS NOT DISTINCT FROM} — {@code NULL = NULL} 은 참이 아니라 UNKNOWN 이다.
	 *       기관이 있는 초대에서는 {@code =} 와 동작이 같다.</li>
	 *   <li>기관 상태 검사는 <b>기관이 있을 때만</b> 적용한다.</li>
	 *   <li>{@code FOR UPDATE OF} 에 {@code o} 를 넣지 않는다. PostgreSQL 은 outer join 의
	 *       nullable 쪽에 FOR UPDATE 를 걸 수 없다.</li>
	 * </ul>
	 */
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
			LEFT JOIN organization o ON o.org_id = ott.org_id
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
				AND u.org_id IS NOT DISTINCT FROM ott.org_id
				AND u.normalized_email = ott.target_email_normalized
				AND (ott.org_id IS NULL OR (o.status = 'ACTIVE' AND o.deleted_at IS NULL))
			FOR UPDATE OF ott, ui, u
			""", nativeQuery = true)
	Optional<AccountActivationProjection> findActivationTargetForUpdate(
			@Param("tokenHash") String tokenHash,
			@Param("userId") UUID userId,
			@Param("purpose") String purpose,
			@Param("activatedAt") Instant activatedAt
	);

	/**
	 * 가입 화면이 초대 링크를 열어 이메일을 표시하는 단계다. 여기서 막히면 사용자가 가입 화면에
	 * 도달조차 못 하므로 세 초대 목적을 모두 해석한다.
	 *
	 * <p>기관 조인·비교는 {@link #findActivationTargetForUpdate}와 같은 이유로 NULL 을 허용한다.
	 */
	@Query(value = """
			SELECT
				u.user_id AS "userId",
				CAST(u.email AS text) AS "email",
				r.code AS "roleCode"
			FROM one_time_token ott
			JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
			JOIN app_user u ON u.user_id = ott.user_id
			JOIN "role" r ON r.role_id = u.role_id
			LEFT JOIN organization o ON o.org_id = ott.org_id
			WHERE ott.token_hash = :tokenHash
				AND ott.purpose IN ('INVITE_SUPER_ADMIN', 'INVITE_OPERATOR_MANAGER', 'INVITE_TRAINEE')
				AND ui.current_token_id = ott.token_id
				AND ui.status = 'SENT'
				AND ott.used_at IS NULL
				AND ott.invalidated_at IS NULL
				AND ott.expires_at > :resolvedAt
				AND u.status = 'PENDING'
				AND u.deleted_at IS NULL
				AND u.org_id IS NOT DISTINCT FROM ott.org_id
				AND u.normalized_email = ott.target_email_normalized
				AND (ott.org_id IS NULL OR (o.status = 'ACTIVE' AND o.deleted_at IS NULL))
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

	/**
	 * 링크 진입 시 토큰이 아직 쓸 수 있는지만 본다. 확정 경로가 아니므로 행을 잠그지 않는다 —
	 * 사용자가 메일 링크를 여는 것만으로 재설정 확정 트랜잭션이 대기하게 만들면 안 된다.
	 */
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
			""", nativeQuery = true)
	Optional<PasswordResetTokenProjection> findPasswordResetToken(@Param("tokenHash") String tokenHash);

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
		String getRoleCode();
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
