package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcMemberInvitationRepository implements MemberInvitationRepository {
	private static final String FIND_ACTIVE_ORGANIZATION = """
			SELECT org_id, name
			FROM organization
			WHERE org_id = ?
				AND status = 'ACTIVE'
				AND deleted_at IS NULL
			""";
	private static final String FIND_INVITABLE_COHORT = """
			SELECT c.org_id, o.name AS organization_name, c.cohort_id, c.name AS cohort_name
			FROM cohort c
			JOIN organization o ON o.org_id = c.org_id
			WHERE c.cohort_id = ?
				AND c.status <> 'CLOSED'
				AND c.deleted_at IS NULL
				AND o.status = 'ACTIVE'
				AND o.deleted_at IS NULL
			""";
	private static final String EXISTS_USER = """
			SELECT EXISTS (
				SELECT 1
				FROM app_user
				WHERE normalized_email = ?
			)
			""";
	private static final String EXISTS_INCOMPLETE_INVITATION = """
			SELECT EXISTS (
				SELECT 1
				FROM user_invitation
				WHERE target_email_normalized = ?
					AND status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
			)
			""";
	private static final String EXISTS_ORGANIZATION_TRAINEE = """
			SELECT EXISTS (
				SELECT 1
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				JOIN organization o ON o.org_id = u.org_id
				WHERE u.normalized_email = ?
					AND u.org_id = ?
					AND r.code = 'TRAINEE'
					AND u.deleted_at IS NULL
					AND o.deleted_at IS NULL
			)
			""";
	private static final String INSERT_PENDING_USER = """
			INSERT INTO app_user (
				user_id, org_id, role_id, email, normalized_email, name, password_hash,
				status, is_email_verified, failed_login_count, password_changed_at,
				created_at, updated_at, row_version
			) VALUES (
				?, ?, (SELECT role_id FROM "role" WHERE code = ?), ?, ?, ?, ?,
				'PENDING', FALSE, 0, ?, ?, ?, 0
			)
			""";
	private static final String INSERT_TOKEN = """
			INSERT INTO one_time_token (
				token_id, org_id, user_id, invitation_id, target_email, target_email_normalized,
				purpose, token_hash, payload, issued_at, expires_at, used_at,
				invalidated_at, invalidated_reason, replaced_by_token_id,
				issued_by, issued_request_id, used_request_id, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NULL, NULL, NULL, NULL, ?, ?, NULL, ?)
			""";
	private static final String INSERT_INVITATION = """
			INSERT INTO user_invitation (
				invitation_id, org_id, target_email, target_email_normalized,
				target_role_code, target_cohort_id, status,
				invited_by, invited_at, resend_count, created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0, ?, ?)
			""";
	private static final String MARK_INVITATION_SENT = """
			UPDATE user_invitation
			SET status = 'SENT',
				current_token_id = ?,
				sent_at = ?,
				failure_stage = NULL,
				failure_code = NULL,
				failure_reason = NULL,
				failed_at = NULL,
				updated_at = ?
			WHERE invitation_id = ?
				AND status = 'PENDING'
			""";
	/*
	 * org_id 비교에 `IS NOT DISTINCT FROM`을 쓴다. 슈퍼어드민 초대는 org_id가 NULL인데
	 * `org_id = ?`로 비교하면 NULL = NULL이 UNKNOWN이라 WHERE가 한 행도 잡지 못한다.
	 * 그러면 같은 주소로 재초대할 때 이전 토큰이 살아남아 유효한 링크가 둘이 된다.
	 * 기관 초대(org_id NOT NULL)에서는 `=`와 동작이 같다.
	 */
	private static final String INVALIDATE_PREVIOUS_TOKENS = """
			UPDATE one_time_token
			SET invalidated_at = ?,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = ?
			WHERE org_id IS NOT DISTINCT FROM ?
				AND target_email_normalized = ?
				AND purpose = ?
				AND token_id <> ?
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""";
	private static final String INSERT_COHORT_MEMBER = """
			INSERT INTO cohort_member (
				cohort_member_id, cohort_id, user_id, org_id, joined_at,
				left_at, status, created_at
			) VALUES (?, ?, ?, ?, ?, NULL, 'INVITED', ?)
			""";
	private static final String INSERT_CLASS_MEMBERSHIP = """
			INSERT INTO class_membership (
				class_membership_id, class_id, cohort_member_id, org_id,
				assigned_at, unassigned_at, assigned_by, created_at
			) VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<InvitationContext> findActiveOrganization(UUID organizationId) {
		return jdbcTemplate.query(
				FIND_ACTIVE_ORGANIZATION,
				(rs, rowNum) -> InvitationContext.organization(
						rs.getObject("org_id", UUID.class),
						rs.getString("name")
				),
				organizationId
		).stream().findFirst();
	}

	@Override
	public Optional<InvitationContext> findInvitableCohort(UUID cohortId) {
		return jdbcTemplate.query(
				FIND_INVITABLE_COHORT,
				(rs, rowNum) -> new InvitationContext(
						rs.getObject("org_id", UUID.class),
						rs.getString("organization_name"),
						rs.getObject("cohort_id", UUID.class),
						rs.getString("cohort_name")
				),
				cohortId
		).stream().findFirst();
	}

	@Override
	public boolean existsUserByNormalizedEmail(String normalizedEmail) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(EXISTS_USER, Boolean.class, normalizedEmail));
	}

	@Override
	public boolean existsIncompleteInvitationByNormalizedEmail(String normalizedEmail) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				EXISTS_INCOMPLETE_INVITATION,
				Boolean.class,
				normalizedEmail
		));
	}

	@Override
	public boolean existsOrganizationTraineeByNormalizedEmail(UUID organizationId, String normalizedEmail) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				EXISTS_ORGANIZATION_TRAINEE,
				Boolean.class,
				normalizedEmail,
				organizationId
		));
	}

	@Override
	public void validateCohort(UUID organizationId, UUID cohortId) {
		Integer cohortCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM cohort WHERE cohort_id = ? AND org_id = ? AND deleted_at IS NULL",
				Integer.class,
				cohortId,
				organizationId
		);
		if (cohortCount == null || cohortCount != 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기관에 속하지 않은 기수입니다.");
		}
	}

	@Override
	public UUID createPendingUser(
			UUID organizationId,
			String email,
			String normalizedEmail,
			String name,
			Role role,
			String passwordHash,
			Instant now
	) {
		UUID userId = UUID.randomUUID();
		Timestamp timestamp = Timestamp.from(now);
		int inserted = jdbcTemplate.update(
				INSERT_PENDING_USER,
				userId,
				organizationId,
				role.name(),
				email,
				normalizedEmail,
				name,
				passwordHash,
				timestamp,
				timestamp,
				timestamp
		);
		if (inserted != 1) {
			throw new IllegalStateException("초대 대상 계정을 생성할 수 없습니다.");
		}
		return userId;
	}

	@Override
	public UUID createInvitation(
			UUID organizationId,
			String email,
			String normalizedEmail,
			Role targetRole,
			UUID targetCohortId,
			UUID invitedBy,
			Instant invitedAt
	) {
		UUID invitationId = UUID.randomUUID();
		Timestamp timestamp = Timestamp.from(invitedAt);
		int inserted = jdbcTemplate.update(
				INSERT_INVITATION,
				invitationId,
				organizationId,
				email,
				normalizedEmail,
				targetRole.name(),
				targetCohortId,
				invitedBy,
				timestamp,
				timestamp,
				timestamp
		);
		if (inserted != 1) {
			throw new IllegalStateException("초대 원장을 생성할 수 없습니다.");
		}
		return invitationId;
	}

	@Override
	public void saveToken(InvitationToken token) {
		jdbcTemplate.update(
				INSERT_TOKEN,
				token.tokenId(),
				token.organizationId(),
				token.userId(),
				token.invitationId(),
				token.targetEmail(),
				token.normalizedTargetEmail(),
				token.purpose().name(),
				token.tokenHash(),
				token.payload(),
				Timestamp.from(token.issuedAt()),
				Timestamp.from(token.expiresAt()),
				token.issuedBy(),
				token.issuedRequestId(),
				Timestamp.from(token.issuedAt())
		);
	}

	@Override
	public void markInvitationSent(UUID invitationId, UUID tokenId, Instant sentAt) {
		Timestamp timestamp = Timestamp.from(sentAt);
		int updated = jdbcTemplate.update(
				MARK_INVITATION_SENT,
				tokenId,
				timestamp,
				timestamp,
				invitationId
		);
		if (updated != 1) {
			throw new IllegalStateException("초대 발송 상태를 저장할 수 없습니다.");
		}
	}

	@Override
	public void invalidatePreviousTokens(InvitationToken replacement, Instant invalidatedAt) {
		jdbcTemplate.update(
				INVALIDATE_PREVIOUS_TOKENS,
				Timestamp.from(invalidatedAt),
				replacement.tokenId(),
				replacement.organizationId(),
				replacement.normalizedTargetEmail(),
				replacement.purpose().name(),
				replacement.tokenId()
		);
	}

	@Override
	public void saveTraineeMembership(
			UUID memberId,
			UUID tokenId,
			UUID organizationId,
			UUID cohortId,
			UUID classroomId,
			UUID assignedBy,
			Instant joinedAt
	) {
		UUID cohortMemberId = UUID.randomUUID();
		Timestamp timestamp = Timestamp.from(joinedAt);
		jdbcTemplate.update(
				INSERT_COHORT_MEMBER,
				cohortMemberId,
				cohortId,
				memberId,
				organizationId,
				timestamp,
				timestamp
		);
		if (classroomId != null) {
			jdbcTemplate.update(
					INSERT_CLASS_MEMBERSHIP,
					UUID.randomUUID(),
					classroomId,
					cohortMemberId,
					organizationId,
					timestamp,
					assignedBy,
					timestamp
			);
		}
	}

}
