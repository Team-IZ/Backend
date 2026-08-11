package com.bigproject.backend.domain.auth.infrastructure.jpa;

import com.bigproject.backend.domain.academicoperations.domain.CohortMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuthCohortMemberJpaRepository extends JpaRepository<CohortMember, UUID> {
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			WITH target_membership AS (
				SELECT ui.org_id, ui.target_cohort_id AS cohort_id, ott.user_id
				FROM one_time_token ott
				JOIN user_invitation ui ON ui.invitation_id = ott.invitation_id
				JOIN cohort c ON c.cohort_id = ui.target_cohort_id
				WHERE ott.token_id = :tokenId
					AND ott.user_id = :userId
					AND ott.purpose = 'INVITE_TRAINEE'
					AND ott.used_at IS NULL
					AND ott.invalidated_at IS NULL
					AND ui.current_token_id = ott.token_id
					AND ui.status = 'SENT'
					AND ui.target_role_code = 'TRAINEE'
					AND ui.org_id IS NOT DISTINCT FROM ott.org_id
					AND c.org_id = ui.org_id
					AND c.status <> 'CLOSED'
					AND c.deleted_at IS NULL
				FOR SHARE OF c
			)
			INSERT INTO cohort_member (
				cohort_member_id, org_id, cohort_id, user_id,
				joined_at, left_at, status, created_at
			)
			SELECT gen_random_uuid(), target.org_id, target.cohort_id, target.user_id,
			       :activatedAt, NULL, 'ACTIVE', :activatedAt
			FROM target_membership target
			ON CONFLICT (cohort_id, user_id) DO NOTHING
			""", nativeQuery = true)
	int createActiveFromInvitation(
			@Param("userId") UUID userId,
			@Param("tokenId") UUID tokenId,
			@Param("activatedAt") Instant activatedAt
	);
}
