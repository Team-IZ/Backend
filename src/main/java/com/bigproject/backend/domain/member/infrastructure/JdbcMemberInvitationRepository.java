package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
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
	/**
	 * 재사용 가능한 계정 자리 조회. is_email_verified = FALSE 가 "한 번도 활성화된 적 없음"을 뜻한다
	 * (자리 생성 시 FALSE, 활성화 시 TRUE). password_changed_at 은 자리 생성 시점에 이미 채워지므로 쓸 수 없다.
	 */
	private static final String FIND_REUSABLE_INVITED_USER = """
			SELECT user_id
			FROM app_user
			WHERE normalized_email = ?
				AND deleted_at IS NULL
				AND status = 'INACTIVE'
				AND is_email_verified = FALSE
				AND last_login_at IS NULL
			""";
	/** 취소돼 INACTIVE 로 내려간 자리를 재초대용으로 되살린다. 소속·역할·이름을 이번 초대 기준으로 덮어쓴다. */
	private static final String REACTIVATE_INVITED_USER = """
			UPDATE app_user
			SET org_id = ?,
				role_id = (SELECT role_id FROM "role" WHERE code = ?),
				email = ?,
				normalized_email = ?,
				name = ?,
				password_hash = ?,
				status = 'PENDING',
				inactivated_at = NULL,
				inactivated_by = NULL,
				inactivated_reason_code = NULL,
				inactivated_reason = NULL,
				failed_login_count = 0,
				login_blocked_until = NULL,
				password_changed_at = ?,
				updated_at = ?,
				row_version = row_version + 1
			WHERE user_id = ?
				AND deleted_at IS NULL
				AND status = 'INACTIVE'
				AND is_email_verified = FALSE
			""";
	/**
	 * 메일 발송 실패 기록. ck_user_invitation_updated_at_5 가 status·failure_stage·failure_code·failed_at 을
	 * 한 세트로 요구하므로 넷을 함께 쓴다. current_token_id 도 채워 [재발송]이 어느 토큰을 대체하는지 남긴다.
	 */
	private static final String MARK_INVITATION_DELIVERY_FAILED = """
			UPDATE user_invitation
			SET status = 'DELIVERY_FAILED',
				current_token_id = ?,
				failure_stage = 'MAIL_DELIVERY',
				failure_code = 'INVITE_MAIL_FAILED',
				failure_reason = ?,
				failed_at = ?,
				updated_at = ?
			WHERE invitation_id = ?
				AND status IN ('PENDING', 'SENT')
			""";
	/**
	 * 재발송 대상 조회. 만료된 토큰도 포함한다 — 만료가 재발송의 주된 사유다.
	 * 이미 수락(ACCEPTED)·취소(CANCELLED)·만료 처리(EXPIRED)된 초대는 되살리지 않는다.
	 */
	private static final String FIND_RESENDABLE_INVITATION = """
			SELECT ui.invitation_id,
			       t.user_id,
			       ui.target_email,
			       ui.target_email_normalized,
			       u.name,
			       ui.target_role_code,
			       t.purpose,
			       ui.org_id,
			       o.name AS org_name,
			       ui.target_cohort_id,
			       c.name AS cohort_name
			FROM one_time_token t
			JOIN user_invitation ui ON ui.invitation_id = t.invitation_id
			JOIN app_user u ON u.user_id = t.user_id
			LEFT JOIN organization o ON o.org_id = ui.org_id
			LEFT JOIN cohort c ON c.cohort_id = ui.target_cohort_id
			WHERE t.token_id = ?
				AND t.used_at IS NULL
				AND ui.status IN ('PENDING', 'SENT', 'DELIVERY_FAILED')
				AND u.deleted_at IS NULL
			""";
	/** 재발송 성공 기록. DELIVERY_FAILED에서 올라올 수 있으므로 실패 컬럼 4개를 함께 비운다. */
	private static final String MARK_INVITATION_RESENT = """
			UPDATE user_invitation
			SET status = 'SENT',
				current_token_id = ?,
				sent_at = ?,
				failure_stage = NULL,
				failure_code = NULL,
				failure_reason = NULL,
				failed_at = NULL,
				resend_count = resend_count + 1,
				last_resend_at = ?,
				updated_at = ?
			WHERE invitation_id = ?
				AND status IN ('PENDING', 'SENT', 'DELIVERY_FAILED')
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
			throw new ApiException(MemberErrorCode.COHORT_NOT_IN_ORGANIZATION);
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
	public Optional<UUID> findReusableInvitedUser(String normalizedEmail) {
		return jdbcTemplate.query(
				FIND_REUSABLE_INVITED_USER,
				(ResultSet rs, int rowNum) -> rs.getObject("user_id", UUID.class),
				normalizedEmail
		).stream().findFirst();
	}

	@Override
	public void reactivateInvitedUser(
			UUID userId,
			UUID organizationId,
			String email,
			String normalizedEmail,
			String name,
			Role role,
			String passwordHash,
			Instant now
	) {
		Timestamp timestamp = Timestamp.from(now);
		int updated = jdbcTemplate.update(
				REACTIVATE_INVITED_USER,
				organizationId,
				role.name(),
				email,
				normalizedEmail,
				name,
				passwordHash,
				timestamp,
				timestamp,
				userId
		);
		if (updated != 1) {
			// 조회와 갱신 사이에 그 자리가 활성화됐다는 뜻이다. 덮어쓰지 않고 중복 초대로 처리한다.
			throw new IllegalStateException("재초대할 계정 자리를 되살릴 수 없습니다.");
		}
	}

	@Override
	public void markInvitationDeliveryFailed(UUID invitationId, UUID tokenId, String failureReason, Instant failedAt) {
		Timestamp timestamp = Timestamp.from(failedAt);
		jdbcTemplate.update(
				MARK_INVITATION_DELIVERY_FAILED,
				tokenId,
				failureReason,
				timestamp,
				timestamp,
				invitationId
		);
		// 갱신 행이 0이어도 예외로 올리지 않는다. 이 메서드는 이미 실패한 발송을 기록하는 자리라,
		// 여기서 다시 던지면 원래 실패 원인이 가려진다.
	}

	@Override
	public Optional<ResendableInvitation> findResendableInvitation(UUID tokenId) {
		return jdbcTemplate.query(
				FIND_RESENDABLE_INVITATION,
				(ResultSet rs, int rowNum) -> new ResendableInvitation(
						rs.getObject("invitation_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getString("target_email"),
						rs.getString("target_email_normalized"),
						rs.getString("name"),
						Role.valueOf(rs.getString("target_role_code")),
						InvitationPurpose.valueOf(rs.getString("purpose")),
						rs.getObject("org_id", UUID.class),
						rs.getString("org_name"),
						rs.getObject("target_cohort_id", UUID.class),
						rs.getString("cohort_name")
				),
				tokenId
		).stream().findFirst();
	}

	@Override
	public void markInvitationResent(UUID invitationId, UUID tokenId, Instant sentAt) {
		Timestamp timestamp = Timestamp.from(sentAt);
		int updated = jdbcTemplate.update(
				MARK_INVITATION_RESENT,
				tokenId,
				timestamp,
				timestamp,
				timestamp,
				invitationId
		);
		if (updated != 1) {
			throw new IllegalStateException("초대 재발송 상태를 저장할 수 없습니다.");
		}
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
