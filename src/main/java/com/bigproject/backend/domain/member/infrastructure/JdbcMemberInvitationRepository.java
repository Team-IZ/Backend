package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.ManagerAssignmentRequest;
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
	private static final String INSERT_PENDING_USER = """
			INSERT INTO app_user (
				user_id, org_id, role_id, email, normalized_email, name, password_hash,
				status, is_email_verified, failed_login_count, password_changed_at,
				commit_email_status, created_at, updated_at, row_version
			) VALUES (
				?, ?, (SELECT role_id FROM "role" WHERE code = ?), ?, ?, ?, ?,
				'PENDING', FALSE, 0, ?, 'UNVERIFIED', ?, ?, 0
			)
			""";
	private static final String INSERT_TOKEN = """
			INSERT INTO one_time_token (
				token_id, org_id, user_id, target_email, target_email_normalized,
				purpose, token_hash, payload, issued_at, expires_at, used_at,
				invalidated_at, invalidated_reason, replaced_by_token_id,
				issued_by, issued_request_id, used_request_id, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NULL, NULL, NULL, NULL, ?, ?, '', ?)
			""";
	private static final String INVALIDATE_PREVIOUS_TOKENS = """
			UPDATE one_time_token
			SET invalidated_at = ?,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = ?
			WHERE org_id = ?
				AND target_email_normalized = ?
				AND purpose = ?
				AND token_id <> ?
				AND used_at IS NULL
				AND invalidated_at IS NULL
			""";
	private static final String INSERT_MANAGER_ASSIGNMENT = """
			INSERT INTO manager_assignment (
				assignment_id, manager_user_id, org_id, role_scope, cohort_id,
				class_id, assigned_at, unassigned_at, status, assigned_by, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'ACTIVE', ?, ?)
			""";
	private static final String INSERT_COHORT_MEMBER = """
			INSERT INTO cohort_member (
				cohort_member_id, cohort_id, user_id, org_id, joined_at,
				left_at, status, invitation_token_id, created_at
			) VALUES (?, ?, ?, ?, ?, NULL, 'INVITED', ?, ?)
			""";
	private static final String INSERT_CLASS_MEMBERSHIP = """
			INSERT INTO class_membership (
				class_membership_id, class_id, cohort_member_id, org_id,
				assigned_at, unassigned_at, assignment_batch_id, assigned_by, created_at
			) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)
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
	public void validateManagerAssignments(UUID organizationId, List<ManagerAssignmentRequest> assignments) {
		for (ManagerAssignmentRequest assignment : assignments) {
			Integer cohortCount = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM cohort WHERE cohort_id = ? AND org_id = ? AND deleted_at IS NULL",
					Integer.class,
					assignment.cohortId(),
					organizationId
			);
			if (cohortCount == null || cohortCount != 1) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기관에 속하지 않은 기수가 포함되어 있습니다.");
			}
			Set<UUID> classroomIds = new HashSet<>(assignment.classroomIds());
			for (UUID classroomId : classroomIds) {
				validateClassroom(organizationId, assignment.cohortId(), classroomId);
			}
		}
	}

	@Override
	public void validateClassroom(UUID organizationId, UUID cohortId, UUID classroomId) {
		Integer classCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM \"class\" WHERE class_id = ? AND cohort_id = ? AND org_id = ? AND deleted_at IS NULL",
				Integer.class,
				classroomId,
				cohortId,
				organizationId
		);
		if (classCount == null || classCount != 1) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기수에 속하지 않은 반이 포함되어 있습니다.");
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
	public void saveToken(InvitationToken token) {
		jdbcTemplate.update(
				INSERT_TOKEN,
				token.tokenId(),
				token.organizationId(),
				token.userId(),
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
	public void saveManagerAssignments(
			UUID memberId,
			UUID organizationId,
			UUID assignedBy,
			List<ManagerAssignmentRequest> assignments,
			Instant assignedAt
	) {
		Timestamp timestamp = Timestamp.from(assignedAt);
		for (ManagerAssignmentRequest assignment : assignments) {
			Set<UUID> classroomIds = new HashSet<>(assignment.classroomIds());
			if (classroomIds.isEmpty()) {
				insertManagerAssignment(memberId, organizationId, assignedBy, assignment.cohortId(), null, "COHORT", timestamp);
			} else {
				for (UUID classroomId : classroomIds) {
					insertManagerAssignment(memberId, organizationId, assignedBy, assignment.cohortId(), classroomId, "CLASS", timestamp);
				}
			}
		}
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
				tokenId,
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
					tokenId.toString(),
					assignedBy,
					timestamp
			);
		}
	}

	private void insertManagerAssignment(
			UUID memberId,
			UUID organizationId,
			UUID assignedBy,
			UUID cohortId,
			UUID classroomId,
			String scope,
			Timestamp assignedAt
	) {
		jdbcTemplate.update(
				INSERT_MANAGER_ASSIGNMENT,
				UUID.randomUUID(),
				memberId,
				organizationId,
				scope,
				cohortId,
				classroomId,
				assignedAt,
				assignedBy,
				assignedAt
		);
	}
}
