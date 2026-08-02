package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.AccountActivationRepository;
import com.bigproject.backend.domain.auth.domain.AccountActivationTarget;
import com.bigproject.backend.domain.auth.domain.ConsentRecord;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAccountActivationRepository implements AccountActivationRepository {
	private static final String FIND_TARGET_FOR_UPDATE = """
			SELECT
				ott.token_id,
				u.user_id,
				u.org_id,
				u.email,
				u.name,
				u.row_version,
				r.code AS role_code
			FROM one_time_token ott
			JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
			JOIN app_user u ON u.user_id = ott.user_id
			JOIN "role" r ON r.role_id = u.role_id
			JOIN organization o ON o.org_id = ott.org_id
			WHERE ott.token_hash = ?
				AND ott.user_id = ?
				AND ott.purpose = ?
				AND ui.current_token_id = ott.token_id
				AND ui.status = 'SENT'
				AND ott.used_at IS NULL
				AND ott.invalidated_at IS NULL
				AND ott.expires_at > ?
				AND u.status = 'PENDING'
				AND u.deleted_at IS NULL
				AND u.org_id = ott.org_id
				AND u.normalized_email = ott.target_email_normalized
				AND o.status = 'ACTIVE'
				AND o.deleted_at IS NULL
			FOR UPDATE OF ott, ui, u, o
			""";
	private static final String ACTIVATE_USER = """
			UPDATE app_user
			SET name = ?,
				password_hash = ?,
				status = 'ACTIVE',
				is_email_verified = TRUE,
				email_verified_at = ?,
				failed_login_count = 0,
				login_blocked_until = NULL,
				password_changed_at = ?,
				updated_at = ?,
				row_version = row_version + 1
			WHERE user_id = ?
				AND status = 'PENDING'
				AND deleted_at IS NULL
				AND row_version = ?
			""";
	private static final String INSERT_CONSENT_RECORD = """
			INSERT INTO consent_record (
				consent_id, org_id, user_id, consent_code, policy_version, agreed,
				agreed_at, capture_channel, source_ip,
				user_agent, locale, evidence_hash, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), ?, ?, ?, ?)
			""";
	private static final String ACTIVATE_TRAINEE_MEMBERSHIP = """
			UPDATE cohort_member
			SET status = 'ACTIVE',
				joined_at = ?
			WHERE user_id = ?
				AND status = 'INVITED'
			""";
	private static final String MARK_INVITATION_USED = """
			UPDATE one_time_token
			SET used_at = ?,
				used_request_id = ?
			WHERE token_id = ?
				AND used_at IS NULL
				AND invalidated_at IS NULL
				AND expires_at > ?
			""";
	private static final String MARK_INVITATION_ACCEPTED = """
			UPDATE user_invitation ui
			SET status = 'ACCEPTED',
				accepted_at = ?,
				accepted_user_id = ott.user_id,
				updated_at = ?
			FROM one_time_token ott
			WHERE ui.invitation_id = ott.invitation_id
				AND ott.token_id = ?
				AND ui.current_token_id = ott.token_id
				AND ui.status = 'SENT'
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<AccountActivationTarget> findTargetForUpdate(
			String tokenHash,
			UUID userId,
			InvitationPurpose purpose,
			Instant activatedAt
	) {
		return jdbcTemplate.query(
				FIND_TARGET_FOR_UPDATE,
				(rs, rowNum) -> new AccountActivationTarget(
						rs.getObject("token_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getObject("org_id", UUID.class),
						rs.getString("email"),
						rs.getString("name"),
						Role.valueOf(rs.getString("role_code")),
						rs.getInt("row_version")
				),
				tokenHash,
				userId,
				purpose.name(),
				Timestamp.from(activatedAt)
		).stream().findFirst();
	}

	@Override
	public boolean activateUser(
			UUID userId,
			int expectedRowVersion,
			String name,
			String passwordHash,
			Instant activatedAt
	) {
		Timestamp timestamp = Timestamp.from(activatedAt);
		return jdbcTemplate.update(
				ACTIVATE_USER,
				name,
				passwordHash,
				timestamp,
				timestamp,
				timestamp,
				userId,
				expectedRowVersion
		) == 1;
	}

	@Override
	public void saveConsentRecords(List<ConsentRecord> consentRecords) {
		for (ConsentRecord consent : consentRecords) {
			Timestamp capturedAt = Timestamp.from(consent.capturedAt());
			int inserted = jdbcTemplate.update(
					INSERT_CONSENT_RECORD,
					consent.consentId(),
					consent.organizationId(),
					consent.userId(),
					consent.consentCode().name(),
					consent.policyVersion(),
					consent.agreed(),
					capturedAt,
					consent.captureChannel(),
					consent.sourceIp(),
					consent.userAgent(),
					consent.locale(),
					consent.evidenceHash(),
					capturedAt
			);
			if (inserted != 1) {
				throw new IllegalStateException("사용자 동의 이력을 저장할 수 없습니다.");
			}
		}
	}

	@Override
	public boolean activateTraineeMembership(UUID userId, UUID invitationTokenId, Instant activatedAt) {
		return jdbcTemplate.update(
				ACTIVATE_TRAINEE_MEMBERSHIP,
				Timestamp.from(activatedAt),
				userId
		) == 1;
	}

	@Override
	public boolean markInvitationUsed(UUID tokenId, String requestId, Instant usedAt) {
		Timestamp timestamp = Timestamp.from(usedAt);
		if (jdbcTemplate.update(MARK_INVITATION_USED, timestamp, requestId, tokenId, timestamp) != 1) {
			return false;
		}
		return jdbcTemplate.update(MARK_INVITATION_ACCEPTED, timestamp, timestamp, tokenId) == 1;
	}
}
