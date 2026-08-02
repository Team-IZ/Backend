package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.domain.PasswordResetToken;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcPasswordResetRepository implements PasswordResetRepository {
	private static final String FIND_ACCOUNT = """
			SELECT
				u.user_id,
				u.org_id,
				o.name AS organization_name,
				u.email,
				u.name,
				u.normalized_email,
				u.password_hash,
				u.status,
				r.code AS role_code,
				ui.invitation_id,
				ui.target_cohort_id,
				c.name AS cohort_name
			FROM app_user u
			JOIN "role" r ON r.role_id = u.role_id
			LEFT JOIN organization o ON o.org_id = u.org_id
			LEFT JOIN user_invitation ui ON ui.invitation_id = (
				SELECT latest_ui.invitation_id
				FROM user_invitation latest_ui
				WHERE latest_ui.target_email_normalized = u.normalized_email
					AND latest_ui.status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
				ORDER BY latest_ui.invited_at DESC, latest_ui.created_at DESC
				LIMIT 1
			)
			LEFT JOIN cohort c ON c.cohort_id = ui.target_cohort_id
			WHERE u.normalized_email = ?
				AND u.deleted_at IS NULL
			""";
	private static final String HAS_RECENT_REQUEST = """
			SELECT EXISTS (
				SELECT 1
				FROM audit_log
				WHERE event_code = 'AUTH.PASSWORD_RESET_REQUEST'
					AND target_type = 'APP_USER'
					AND target_id = ?
					AND result = 'SUCCESS'
					AND occurred_at >= ?
			)
			""";
	private static final String INSERT_TOKEN = """
			INSERT INTO one_time_token (
				token_id, org_id, user_id, invitation_id, target_email, target_email_normalized,
				purpose, token_hash, payload, issued_at, expires_at, used_at,
				invalidated_at, invalidated_reason, replaced_by_token_id,
				issued_by, issued_request_id, used_request_id, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NULL, NULL, NULL, NULL, NULL, ?, NULL, ?)
			""";
	private static final String INVALIDATE_PREVIOUS_RESET_TOKENS = """
			UPDATE one_time_token
			SET invalidated_at = ?,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = ?
			WHERE user_id = ?
				AND purpose = 'PASSWORD_RESET'
				AND token_id <> ?
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""";
	private static final String INVALIDATE_PREVIOUS_INVITATION_TOKENS = """
			UPDATE one_time_token
			SET invalidated_at = ?,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = ?
			WHERE invitation_id = ?
				AND token_id <> ?
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""";
	private static final String REPLACE_CURRENT_INVITATION_TOKEN = """
			UPDATE user_invitation
			SET current_token_id = ?,
				status = 'SENT',
				sent_at = ?,
				resend_count = resend_count + 1,
				last_resend_at = ?,
				failure_stage = NULL,
				failure_code = NULL,
				failure_reason = NULL,
				failed_at = NULL,
				updated_at = ?
			WHERE invitation_id = ?
				AND status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
			""";
	private static final String FIND_TOKEN_FOR_UPDATE = """
			SELECT
				ott.token_id,
				ott.user_id,
				ott.org_id,
				ott.target_email,
				ott.target_email_normalized,
				u.password_hash,
				u.status AS user_status,
				ott.purpose,
				ott.expires_at,
				ott.used_at,
				ott.invalidated_at
			FROM one_time_token ott
			LEFT JOIN app_user u ON u.user_id = ott.user_id AND u.deleted_at IS NULL
			WHERE ott.token_hash = ?
			FOR UPDATE OF ott
			""";
	private static final String UPDATE_PASSWORD = """
			UPDATE app_user
			SET password_hash = ?,
				password_changed_at = ?,
				failed_login_count = 0,
				login_blocked_until = NULL,
				updated_at = ?,
				row_version = row_version + 1
			WHERE user_id = ?
				AND status = 'ACTIVE'
				AND deleted_at IS NULL
			""";
	private static final String MARK_TOKEN_USED = """
			UPDATE one_time_token
			SET used_at = ?, used_request_id = ?
			WHERE token_id = ?
				AND purpose = 'PASSWORD_RESET'
				AND used_at IS NULL
				AND invalidated_at IS NULL
				AND expires_at > ?
			""";
	private static final String INVALIDATE_OTHER_RESET_TOKENS = """
			UPDATE one_time_token
			SET invalidated_at = ?, invalidated_reason = 'PASSWORD_CHANGED'
			WHERE user_id = ?
				AND purpose = 'PASSWORD_RESET'
				AND token_id <> ?
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""";
	private static final String REVOKE_ALL_REFRESH_TOKENS = """
			UPDATE refresh_token
			SET revoked_at = ?, revoked_reason = 'PASSWORD_CHANGED'
			WHERE user_id = ? AND revoked_at IS NULL
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<PasswordResetAccount> findAccountByNormalizedEmail(String normalizedEmail) {
		return jdbcTemplate.query(
				FIND_ACCOUNT,
				(rs, rowNum) -> new PasswordResetAccount(
						rs.getObject("user_id", UUID.class),
						rs.getObject("org_id", UUID.class),
						rs.getString("organization_name"),
						rs.getString("email"),
						rs.getString("name"),
						rs.getString("normalized_email"),
						rs.getString("password_hash"),
						rs.getString("status"),
						Role.valueOf(rs.getString("role_code")),
						rs.getObject("invitation_id", UUID.class),
						rs.getObject("target_cohort_id", UUID.class),
						rs.getString("cohort_name")
				),
				normalizedEmail
		).stream().findFirst();
	}

	@Override
	public boolean hasRecentRequest(UUID userId, Instant requestedAfter) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				HAS_RECENT_REQUEST,
				Boolean.class,
				userId.toString(),
				Timestamp.from(requestedAfter)
		));
	}

	@Override
	public void saveResetToken(UUID tokenId, PasswordResetAccount account, String tokenHash, Instant issuedAt, Instant expiresAt, String requestId) {
		insertToken(tokenId, account, null, InvitationPurpose.PASSWORD_RESET, tokenHash, issuedAt, expiresAt, requestId);
	}

	@Override
	public void invalidatePreviousResetTokens(UUID userId, UUID replacementTokenId, Instant invalidatedAt) {
		jdbcTemplate.update(
				INVALIDATE_PREVIOUS_RESET_TOKENS,
				Timestamp.from(invalidatedAt),
				replacementTokenId,
				userId,
				replacementTokenId
		);
	}

	@Override
	public void saveReplacementInvitationToken(UUID tokenId, PasswordResetAccount account, InvitationPurpose purpose, String tokenHash, Instant issuedAt, Instant expiresAt, String requestId) {
		insertToken(tokenId, account, account.invitationId(), purpose, tokenHash, issuedAt, expiresAt, requestId);
		jdbcTemplate.update(
				INVALIDATE_PREVIOUS_INVITATION_TOKENS,
				Timestamp.from(issuedAt),
				tokenId,
				account.invitationId(),
				tokenId
		);
	}

	@Override
	public void replaceCurrentInvitationToken(UUID invitationId, UUID tokenId, Instant sentAt) {
		Timestamp timestamp = Timestamp.from(sentAt);
		if (jdbcTemplate.update(REPLACE_CURRENT_INVITATION_TOKEN, tokenId, timestamp, timestamp, timestamp, invitationId) != 1) {
			throw new IllegalStateException("활성화 초대 토큰을 교체할 수 없습니다.");
		}
	}

	@Override
	public Optional<PasswordResetToken> findTokenForUpdate(String tokenHash) {
		return jdbcTemplate.query(
				FIND_TOKEN_FOR_UPDATE,
				(rs, rowNum) -> new PasswordResetToken(
						rs.getObject("token_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getObject("org_id", UUID.class),
						rs.getString("target_email"),
						rs.getString("target_email_normalized"),
						rs.getString("password_hash"),
						rs.getString("user_status"),
						rs.getString("purpose"),
						rs.getTimestamp("expires_at").toInstant(),
						instant(rs.getTimestamp("used_at")),
						instant(rs.getTimestamp("invalidated_at"))
				),
				tokenHash
		).stream().findFirst();
	}

	@Override
	public boolean updatePassword(UUID userId, String passwordHash, Instant changedAt) {
		Timestamp timestamp = Timestamp.from(changedAt);
		return jdbcTemplate.update(UPDATE_PASSWORD, passwordHash, timestamp, timestamp, userId) == 1;
	}

	@Override
	public boolean markTokenUsed(UUID tokenId, String requestId, Instant usedAt) {
		Timestamp timestamp = Timestamp.from(usedAt);
		return jdbcTemplate.update(MARK_TOKEN_USED, timestamp, requestId, tokenId, timestamp) == 1;
	}

	@Override
	public void invalidateOtherResetTokens(UUID userId, UUID usedTokenId, Instant invalidatedAt) {
		jdbcTemplate.update(INVALIDATE_OTHER_RESET_TOKENS, Timestamp.from(invalidatedAt), userId, usedTokenId);
	}

	@Override
	public void revokeAllRefreshTokens(UUID userId, Instant revokedAt) {
		jdbcTemplate.update(REVOKE_ALL_REFRESH_TOKENS, Timestamp.from(revokedAt), userId);
	}

	private void insertToken(
			UUID tokenId,
			PasswordResetAccount account,
			UUID invitationId,
			InvitationPurpose purpose,
			String tokenHash,
			Instant issuedAt,
			Instant expiresAt,
			String requestId
	) {
		int inserted = jdbcTemplate.update(
				INSERT_TOKEN,
				tokenId,
				account.organizationId(),
				account.userId(),
				invitationId,
				account.email(),
				account.normalizedEmail(),
				purpose.name(),
				tokenHash,
				"{}",
				Timestamp.from(issuedAt),
				Timestamp.from(expiresAt),
				requestId,
				Timestamp.from(issuedAt)
		);
		if (inserted != 1) {
			throw new IllegalStateException("일회성 토큰을 저장할 수 없습니다.");
		}
	}

	private Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
