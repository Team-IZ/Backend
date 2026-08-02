package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.InvitationRecipient;
import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInvitationResolveRepository implements InvitationResolveRepository {
	private static final String FIND_RESOLVABLE_INVITATION = """
			SELECT u.user_id, u.email
			FROM one_time_token ott
			JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
			JOIN app_user u ON u.user_id = ott.user_id
			JOIN organization o ON o.org_id = ott.org_id
			WHERE ott.token_hash = ?
				AND ott.purpose IN ('INVITE_OPERATOR_MANAGER', 'INVITE_TRAINEE')
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
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<InvitationRecipient> findResolvableByTokenHash(String tokenHash, Instant resolvedAt) {
		return jdbcTemplate.query(
				FIND_RESOLVABLE_INVITATION,
				(rs, rowNum) -> new InvitationRecipient(
						rs.getObject("user_id", UUID.class),
						rs.getString("email")
				),
				tokenHash,
				Timestamp.from(resolvedAt)
		).stream().findFirst();
	}
}
