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
	 * 해석 단계와 활성화 단계가 <b>같은 SELECT</b>를 쓴다. 두 벌로 두면 한쪽만 고쳐져
	 * 링크를 열 때와 제출할 때의 판정이 갈린다. 잠금 절만 활성화 쪽에서 덧붙인다.
	 */
	String INVITATION_STATE_SELECT = """
			SELECT
				ott.token_id AS "tokenId",
				ott.user_id AS "userId",
				ott.purpose AS "purpose",
				ott.expires_at AS "expiresAt",
				ott.used_at AS "usedAt",
				ott.invalidated_at AS "invalidatedAt",
				ott.invalidated_reason AS "invalidatedReason",
				ui.status AS "invitationStatus",
				(ui.current_token_id = ott.token_id) AS "currentToken",
				u.org_id AS "organizationId",
				CAST(u.email AS text) AS "email",
				u.name AS "name",
				u.status AS "userStatus",
				u.row_version AS "rowVersion",
				(u.deleted_at IS NOT NULL) AS "userDeleted",
				(u.normalized_email = ott.target_email_normalized) AS "emailMatched",
				(u.org_id IS NOT DISTINCT FROM ott.org_id) AS "organizationMatched",
				r.code AS "roleCode",
				o.status AS "organizationStatus",
				(o.org_id IS NOT NULL AND o.deleted_at IS NOT NULL) AS "organizationDeleted",
				EXISTS (
					SELECT 1
					FROM cohort c
					WHERE c.cohort_id = ui.target_cohort_id
						AND c.org_id = ui.org_id
						AND ui.target_role_code = 'TRAINEE'
						AND ui.org_id IS NOT DISTINCT FROM ott.org_id
						AND c.status <> 'CLOSED'
						AND c.deleted_at IS NULL
				) AS "onRoster"
			FROM one_time_token ott
			JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
			JOIN app_user u ON u.user_id = ott.user_id
			JOIN "role" r ON r.role_id = u.role_id
			LEFT JOIN organization o ON o.org_id = ott.org_id
			WHERE ott.token_hash = :tokenHash""";

	/**
	 * 초대 토큰의 상태를 통째로 읽는다. 확정 경로이므로 대상 행을 잠근다.
	 *
	 * <p><b>{@code token_hash} 말고는 아무것도 거르지 않는다.</b> 전에는 만료·사용됨·교체됨·
	 * 초대취소·계정상태·기관정지를 모두 {@code WHERE}에 넣고 {@code Optional}을 돌려줬는데,
	 * 그러면 어느 조건에서 떨어졌는지가 사라져 네 가지 상황이 {@code INVITATION_INVALID} 하나로
	 * 접혔다. 지금은 판정 재료를 컬럼으로 올리고 가르는 일은 서비스가 한다.
	 *
	 * <ul>
	 *   <li>{@code JOIN user_invitation} 은 INNER 다. 초대가 아닌 토큰(PASSWORD_RESET)은
	 *       {@code invitation_id}가 NULL 이라 여기서 걸러진다.</li>
	 *   <li>{@code JOIN app_user} 도 INNER 다. PostgreSQL 은 outer join 의 nullable 쪽에
	 *       FOR UPDATE 를 걸 수 없어, 잠글 테이블은 INNER 여야 한다. 계정은 소프트 삭제라
	 *       행 자체는 남으므로 삭제 여부는 {@code userDeleted} 로 올려 판정에 넘긴다.</li>
	 *   <li>{@code LEFT JOIN organization} — 슈퍼어드민 초대는 {@code org_id}가 NULL 이다.
	 *       INNER 면 결과가 항상 0건이라 수락이 막힌다. {@code FOR UPDATE OF} 에서도 뺀다.</li>
	 *   <li>{@code IS NOT DISTINCT FROM} — {@code NULL = NULL} 은 참이 아니라 UNKNOWN 이다.
	 *       기관이 있는 초대에서는 {@code =} 와 동작이 같다.</li>
	 *   <li>교육생 명단 범위는 초대 원장의 기수가 아직 수락 가능한지로 판정한다. 실제
	 *       {@code cohort_member} 행은 수락 트랜잭션에서 생성하므로 여기서 요구하면 모든 신규 초대가
	 *       명단 외로 거절된다.</li>
	 * </ul>
	 */
	@Query(value = INVITATION_STATE_SELECT + "\nFOR UPDATE OF ott, ui, u", nativeQuery = true)
	Optional<InvitationStateProjection> findInvitationStateForUpdate(@Param("tokenHash") String tokenHash);

	/**
	 * 가입 화면이 초대 링크를 열어 이메일을 표시하는 단계다. 확정 경로가 아니므로 잠그지 않는다 —
	 * 사용자가 메일 링크를 여는 것만으로 활성화 트랜잭션이 대기하게 만들면 안 된다.
	 *
	 * <p>목적을 조건에 넣지 않는다. 활성화 단계와 <b>같은 판정</b>을 쓰기 위해서다.
	 */
	@Query(value = INVITATION_STATE_SELECT, nativeQuery = true)
	Optional<InvitationStateProjection> findInvitationState(@Param("tokenHash") String tokenHash);

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

	/**
	 * 판정 재료를 그대로 올린다. 불리언은 {@code Boolean} 으로 받는다 — 기관이 없는 초대에서
	 * NULL 이 올라올 수 있고, {@code boolean} 이면 그 자리에서 NPE 가 난다.
	 */
	interface InvitationStateProjection {
		UUID getTokenId();
		UUID getUserId();
		UUID getOrganizationId();
		String getEmail();
		String getName();
		String getRoleCode();
		int getRowVersion();
		String getPurpose();
		String getInvitationStatus();
		String getUserStatus();
		String getOrganizationStatus();
		String getInvalidatedReason();
		Boolean getCurrentToken();
		Boolean getEmailMatched();
		Boolean getOrganizationMatched();
		Boolean getUserDeleted();
		Boolean getOrganizationDeleted();
		Boolean getOnRoster();
		Instant getExpiresAt();
		Instant getUsedAt();
		Instant getInvalidatedAt();
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
