package com.bigproject.backend.domain.auth.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UserInvitationJpaRepository extends JpaRepository<UserInvitationJpaEntity, UUID> {
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE user_invitation
			SET current_token_id = :tokenId,
				status = 'SENT',
				sent_at = :sentAt,
				resend_count = resend_count + 1,
				last_resend_at = :sentAt,
				failure_stage = NULL,
				failure_code = NULL,
				failure_reason = NULL,
				failed_at = NULL,
				updated_at = :sentAt
			WHERE invitation_id = :invitationId
				AND status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
			""", nativeQuery = true)
	int replaceCurrentToken(
			@Param("invitationId") UUID invitationId,
			@Param("tokenId") UUID tokenId,
			@Param("sentAt") Instant sentAt
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE user_invitation ui
			SET status = 'ACCEPTED',
				accepted_at = :acceptedAt,
				accepted_user_id = ott.user_id,
				updated_at = :acceptedAt
			FROM one_time_token ott
			WHERE ui.invitation_id = ott.invitation_id
				AND ott.token_id = :tokenId
				AND ui.current_token_id = ott.token_id
				AND ui.status = 'SENT'
			""", nativeQuery = true)
	int markAccepted(@Param("tokenId") UUID tokenId, @Param("acceptedAt") Instant acceptedAt);
}
