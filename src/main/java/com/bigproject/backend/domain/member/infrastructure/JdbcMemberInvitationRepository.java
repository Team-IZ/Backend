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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
				JOIN organization o ON o.org_id = u.org_id
				WHERE u.normalized_email = ?
					AND u.org_id = ?
					AND u.role_code = 'TRAINEE'
					AND u.deleted_at IS NULL
					AND o.deleted_at IS NULL
			)
			""";
	/**
	 * 위 EXISTS의 <b>일괄 판정</b>판. 조건은 한 글자도 다르지 않아야 한다 —
	 * 두 판정이 갈리면 미리보기와 등록이 서로 다른 답을 하게 된다.
	 */
	private static final String FIND_EXISTING_ORGANIZATION_TRAINEES = """
			SELECT u.normalized_email
			FROM app_user u
			JOIN organization o ON o.org_id = u.org_id
			WHERE u.normalized_email = ANY(?)
				AND u.org_id = ?
				AND u.role_code = 'TRAINEE'
				AND u.deleted_at IS NULL
				AND o.deleted_at IS NULL
			""";
	private static final String INSERT_PENDING_USER = """
			INSERT INTO app_user (
				user_id, org_id, role_code, email, normalized_email, name, password_hash,
				status, is_email_verified, failed_login_count, password_changed_at,
				created_at, updated_at, row_version
			) VALUES (
				?, ?, ?, ?, ?, ?, ?,
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
	/**
	 * batch_request_id·mail_claimed_at은 <b>일괄 등록에서만</b> 채운다.
	 *
	 * <p>앞은 폴링의 잡 ID이고, 뒤는 "누가 발송을 집었는가"다. 자리를 확보한 인스턴스가 곧바로 발송을
	 * 이어받으므로 적재 시점에 이미 클레임한 것으로 본다 — 비워 두면 안전망 스케줄러가 발송 중인 초대를
	 * 다시 집어 교육생에게 메일이 두 통 간다. 단건 초대는 둘 다 NULL이고, 안전망 조회가
	 * batch_request_id IS NOT NULL만 보므로 단건 경로는 그 조회에 걸리지 않는다.
	 */
	private static final String INSERT_INVITATION = """
			INSERT INTO user_invitation (
				invitation_id, org_id, target_email, target_email_normalized,
				target_role_code, target_cohort_id, status,
				invited_by, invited_at, resend_count, created_at, updated_at,
				batch_request_id, mail_claimed_at
			) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0, ?, ?, ?, ?)
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
				role_code = ?,
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
				AND (ui.target_cohort_id IS NULL OR (c.deleted_at IS NULL AND c.status <> 'CLOSED'))
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
	public Set<String> findExistingOrganizationTraineeEmails(
			UUID organizationId,
			Collection<String> normalizedEmails
	) {
		if (normalizedEmails.isEmpty()) {
			return Set.of();
		}
		String[] emails = normalizedEmails.toArray(new String[0]);
		/*
		 * PreparedStatementSetter로 직접 배열을 만든다. jdbcTemplate.query(sql, args...)에 String[]을
		 * 그대로 넘기면 "파라미터 하나가 배열"이 아니라 "가변인자 여러 개"로 풀려 파라미터 수가 어긋난다.
		 */
		List<String> found = jdbcTemplate.query(
				FIND_EXISTING_ORGANIZATION_TRAINEES,
				(PreparedStatement ps) -> {
					ps.setArray(1, ps.getConnection().createArrayOf("varchar", emails));
					ps.setObject(2, organizationId);
				},
				(ResultSet rs, int rowNum) -> rs.getString("normalized_email")
		);
		return Set.copyOf(found);
	}

	// ---------------------------------------------------------------------
	// 일괄 등록용 벌크 연산. 단건 SQL과 조건이 한 글자도 달라서는 안 된다 —
	// 갈리면 "단건으로는 통과하는데 일괄로는 막히는" 행이 생긴다.
	// ---------------------------------------------------------------------

	private static final String FIND_EMAILS_WITH_INCOMPLETE_INVITATION = """
			SELECT DISTINCT target_email_normalized
			FROM user_invitation
			WHERE target_email_normalized = ANY(?)
				AND status IN ('PENDING', 'SENT', 'DELIVERY_FAILED', 'EXPIRED')
			""";
	private static final String FIND_REUSABLE_INVITED_USERS = """
			SELECT normalized_email, user_id
			FROM app_user
			WHERE normalized_email = ANY(?)
				AND deleted_at IS NULL
				AND status = 'INACTIVE'
				AND is_email_verified = FALSE
				AND last_login_at IS NULL
			""";
	private static final String FIND_EXISTING_USER_EMAILS = """
			SELECT normalized_email
			FROM app_user
			WHERE normalized_email = ANY(?)
			""";
	/**
	 * 단건 INVALIDATE_PREVIOUS_TOKENS의 일괄판.
	 *
	 * <p>(이메일, 새 토큰) 쌍을 배열 두 개로 받아 unnest로 펼쳐 조인한다. 단순히
	 * {@code target_email_normalized = ANY(?)}로 묶으면 {@code replaced_by_token_id}에 넣을 값을
	 * 행마다 고를 수 없어 그 컬럼이 비게 되는데, 단건 경로는 채우고 있어 배치만 다르게 남으면 안 된다.
	 */
	private static final String INVALIDATE_PREVIOUS_TOKENS_FOR_EMAILS = """
			UPDATE one_time_token t
			SET invalidated_at = ?,
				invalidated_reason = 'REPLACED',
				replaced_by_token_id = r.new_token_id
			FROM (SELECT unnest(?::varchar[]) AS email, unnest(?::uuid[]) AS new_token_id) r
			WHERE t.org_id IS NOT DISTINCT FROM ?
				AND t.target_email_normalized = r.email
				AND t.purpose = ?
				AND t.token_id <> r.new_token_id
				AND t.used_at IS NULL
				AND t.invalidated_at IS NULL
			""";

	/**
	 * 잡 진행률. 잡 레코드가 따로 없고 batch_request_id가 같은 행들이 곧 잡이라 집계 한 방이면 끝난다.
	 *
	 * <p>ACCEPTED를 발송 완료로 함께 센다 — 이미 수락한 교육생은 메일을 받았다는 뜻이다. 그렇게 하지 않으면
	 * 발송이 끝났는데도 교육생이 빨리 가입한 만큼 invitationSentCount가 되레 줄어 보인다.
	 */
	private static final String FIND_BATCH_PROGRESS = """
			SELECT count(*)                                            AS registered_count,
			       count(*) FILTER (WHERE status IN ('SENT', 'ACCEPTED')) AS invitation_sent_count,
			       count(*) FILTER (WHERE status = 'DELIVERY_FAILED')  AS mail_failed_count,
			       count(*) FILTER (WHERE status = 'PENDING')          AS mail_pending_count
			FROM user_invitation
			WHERE batch_request_id = ?
				AND org_id = ?
				AND target_cohort_id = ?
			""";
	/**
	 * 발송이 멈춘 초대의 클레임. 배포본이 셋이라 {@code FOR UPDATE SKIP LOCKED}가 필수다 —
	 * 없이 켜면 세 인스턴스가 같은 초대를 집어 교육생에게 메일이 3통 간다.
	 *
	 * <p>클레임과 발송을 분리한다. 발송(수 초)을 트랜잭션 안에서 하면 그동안 행을 잠그게 되므로,
	 * 이 UPDATE 하나로 클레임만 찍고(단일 구문이라 그 자체가 원자적이다) 커밋한 뒤 메일을 보낸다.
	 */
	private static final String CLAIM_STALLED_TRAINEE_INVITATIONS = """
			UPDATE user_invitation
			SET mail_claimed_at = ?,
				updated_at = ?
			WHERE invitation_id IN (
				SELECT invitation_id
				FROM user_invitation
				WHERE status = 'PENDING'
					AND target_role_code = 'TRAINEE'
					AND batch_request_id IS NOT NULL
					AND (mail_claimed_at IS NULL OR mail_claimed_at < ?)
				ORDER BY mail_claimed_at NULLS FIRST, invited_at
				LIMIT ?
				FOR UPDATE SKIP LOCKED
			)
			RETURNING invitation_id
			""";
	/**
	 * 클레임한 초대의 메일 본문 재료. RETURNING은 갱신한 테이블의 컬럼만 돌려주므로 조인이 필요한
	 * 이름·기관명·기수명은 여기서 따로 가져온다.
	 */
	private static final String FIND_CLAIMED_TRAINEE_INVITATIONS = """
			SELECT ui.invitation_id,
			       ui.target_email,
			       ui.target_email_normalized,
			       ui.invited_by,
			       ui.batch_request_id,
			       u.user_id,
			       u.name,
			       o.org_id,
			       o.name AS org_name,
			       c.cohort_id,
			       c.name AS cohort_name
			FROM user_invitation ui
			JOIN app_user u ON u.normalized_email = ui.target_email_normalized
				AND u.org_id = ui.org_id
				AND u.deleted_at IS NULL
			JOIN organization o ON o.org_id = ui.org_id
			JOIN cohort c ON c.cohort_id = ui.target_cohort_id
			WHERE ui.invitation_id = ANY(?)
			""";

	@Override
	public Optional<BatchProgress> findBatchProgress(String batchRequestId, UUID organizationId, UUID cohortId) {
		BatchProgress progress = jdbcTemplate.queryForObject(
				FIND_BATCH_PROGRESS,
				(ResultSet rs, int rowNum) -> new BatchProgress(
						rs.getInt("registered_count"),
						rs.getInt("invitation_sent_count"),
						rs.getInt("mail_failed_count"),
						rs.getInt("mail_pending_count")
				),
				batchRequestId,
				organizationId,
				cohortId
		);
		// 집계는 행이 없어도 0을 담은 한 줄을 돌려준다. 0건은 "그런 배치가 없다"와 같으므로 비워서 올린다.
		return progress == null || progress.registeredCount() == 0 ? Optional.empty() : Optional.of(progress);
	}

	@Override
	public List<StalledInvitation> claimStalledTraineeInvitations(
			Instant claimedAt,
			Instant stalledBefore,
			int limit
	) {
		Timestamp claimedTimestamp = Timestamp.from(claimedAt);
		List<UUID> claimed = jdbcTemplate.query(
				CLAIM_STALLED_TRAINEE_INVITATIONS,
				(PreparedStatement ps) -> {
					ps.setTimestamp(1, claimedTimestamp);
					ps.setTimestamp(2, claimedTimestamp);
					ps.setTimestamp(3, Timestamp.from(stalledBefore));
					ps.setInt(4, limit);
				},
				(ResultSet rs, int rowNum) -> rs.getObject("invitation_id", UUID.class)
		);
		if (claimed.isEmpty()) {
			return List.of();
		}

		UUID[] invitationIds = claimed.toArray(new UUID[0]);
		return jdbcTemplate.query(
				FIND_CLAIMED_TRAINEE_INVITATIONS,
				(PreparedStatement ps) -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", invitationIds)),
				(ResultSet rs, int rowNum) -> new StalledInvitation(
						rs.getObject("invitation_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getString("target_email"),
						rs.getString("target_email_normalized"),
						rs.getString("name"),
						rs.getObject("invited_by", UUID.class),
						rs.getString("batch_request_id"),
						new InvitationContext(
								rs.getObject("org_id", UUID.class),
								rs.getString("org_name"),
								rs.getObject("cohort_id", UUID.class),
								rs.getString("cohort_name")
						)
				)
		);
	}

	@Override
	public Set<String> findEmailsWithIncompleteInvitation(Collection<String> normalizedEmails) {
		return queryEmailSet(FIND_EMAILS_WITH_INCOMPLETE_INVITATION, "target_email_normalized", normalizedEmails);
	}

	@Override
	public Set<String> findExistingUserEmails(Collection<String> normalizedEmails) {
		return queryEmailSet(FIND_EXISTING_USER_EMAILS, "normalized_email", normalizedEmails);
	}

	@Override
	public Map<String, UUID> findReusableInvitedUsers(Collection<String> normalizedEmails) {
		if (normalizedEmails.isEmpty()) {
			return Map.of();
		}
		String[] emails = normalizedEmails.toArray(new String[0]);
		Map<String, UUID> reusable = new HashMap<>();
		jdbcTemplate.query(
				FIND_REUSABLE_INVITED_USERS,
				(PreparedStatement ps) -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", emails)),
				(ResultSet rs) -> {
					reusable.put(rs.getString("normalized_email"), rs.getObject("user_id", UUID.class));
				}
		);
		return reusable;
	}

	private Set<String> queryEmailSet(String sql, String column, Collection<String> normalizedEmails) {
		if (normalizedEmails.isEmpty()) {
			return Set.of();
		}
		String[] emails = normalizedEmails.toArray(new String[0]);
		return Set.copyOf(jdbcTemplate.query(
				sql,
				(PreparedStatement ps) -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", emails)),
				(ResultSet rs, int rowNum) -> rs.getString(column)
		));
	}

	@Override
	public void createPendingUsers(List<NewPendingUser> users) {
		if (users.isEmpty()) {
			return;
		}
		List<Object[]> batch = new ArrayList<>(users.size());
		for (NewPendingUser user : users) {
			Timestamp timestamp = Timestamp.from(user.now());
			batch.add(new Object[]{
					user.userId(), user.organizationId(), user.role().name(), user.email(),
					user.normalizedEmail(), user.name(), user.passwordHash(),
					timestamp, timestamp, timestamp
			});
		}
		verifyBatch(jdbcTemplate.batchUpdate(INSERT_PENDING_USER, batch), "초대 대상 계정");
	}

	@Override
	public void createInvitations(List<NewInvitation> invitations) {
		if (invitations.isEmpty()) {
			return;
		}
		List<Object[]> batch = new ArrayList<>(invitations.size());
		for (NewInvitation invitation : invitations) {
			Timestamp timestamp = Timestamp.from(invitation.invitedAt());
			batch.add(new Object[]{
					invitation.invitationId(), invitation.organizationId(), invitation.email(),
					invitation.normalizedEmail(), invitation.targetRole().name(), invitation.targetCohortId(),
					invitation.invitedBy(), timestamp, timestamp, timestamp,
					invitation.batchRequestId(), mailClaimedAt(invitation.batchRequestId(), timestamp)
			});
		}
		verifyBatch(jdbcTemplate.batchUpdate(INSERT_INVITATION, batch), "초대 원장");
	}

	@Override
	public void saveTokens(List<InvitationToken> tokens) {
		if (tokens.isEmpty()) {
			return;
		}
		List<Object[]> batch = new ArrayList<>(tokens.size());
		for (InvitationToken token : tokens) {
			batch.add(new Object[]{
					token.tokenId(), token.organizationId(), token.userId(), token.invitationId(),
					token.targetEmail(), token.normalizedTargetEmail(), token.purpose().name(),
					token.tokenHash(), token.payload(), Timestamp.from(token.issuedAt()),
					Timestamp.from(token.expiresAt()), token.issuedBy(), token.issuedRequestId(),
					Timestamp.from(token.issuedAt())
			});
		}
		verifyBatch(jdbcTemplate.batchUpdate(INSERT_TOKEN, batch), "초대 토큰");
	}

	@Override
	public void invalidatePreviousTokensForEmails(
			UUID organizationId,
			InvitationPurpose purpose,
			Map<String, UUID> newTokenByEmail,
			Instant invalidatedAt
	) {
		if (newTokenByEmail.isEmpty()) {
			return;
		}
		// 두 배열의 순서가 서로 대응해야 하므로 같은 순회에서 만든다.
		String[] emails = new String[newTokenByEmail.size()];
		UUID[] tokenIds = new UUID[newTokenByEmail.size()];
		int index = 0;
		for (Map.Entry<String, UUID> entry : newTokenByEmail.entrySet()) {
			emails[index] = entry.getKey();
			tokenIds[index] = entry.getValue();
			index++;
		}
		jdbcTemplate.update(INVALIDATE_PREVIOUS_TOKENS_FOR_EMAILS, (PreparedStatement ps) -> {
			ps.setTimestamp(1, Timestamp.from(invalidatedAt));
			ps.setArray(2, ps.getConnection().createArrayOf("varchar", emails));
			ps.setArray(3, ps.getConnection().createArrayOf("uuid", tokenIds));
			ps.setObject(4, organizationId);
			ps.setString(5, purpose.name());
		});
	}

	@Override
	public void markInvitationsSent(List<SentInvitation> invitations, Instant sentAt) {
		if (invitations.isEmpty()) {
			return;
		}
		Timestamp timestamp = Timestamp.from(sentAt);
		List<Object[]> batch = new ArrayList<>(invitations.size());
		for (SentInvitation invitation : invitations) {
			batch.add(new Object[]{
					invitation.tokenId(), timestamp, timestamp, invitation.invitationId()
			});
		}
		// 갱신 0행은 오류가 아니다 — 이미 SENT거나 취소된 초대일 수 있다(단건 SQL도 status='PENDING'만 잡는다).
		jdbcTemplate.batchUpdate(MARK_INVITATION_SENT, batch);
	}

	/**
	 * 배치의 모든 행이 정확히 1건씩 반영됐는지 본다.
	 *
	 * <p>드라이버가 건별 결과를 모르겠다고 답할 수 있어({@code SUCCESS_NO_INFO}) 음수는 성공으로 본다.
	 * 0은 진짜로 적재되지 않은 것이므로 막는다 — 단건 경로가 {@code inserted != 1}을 검사하는 것과 같다.
	 */
	private void verifyBatch(int[] updateCounts, String subject) {
		for (int updateCount : updateCounts) {
			if (updateCount == 0) {
				throw new IllegalStateException(subject + "을 생성할 수 없습니다.");
			}
		}
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
			Instant invitedAt,
			String batchRequestId
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
				timestamp,
				batchRequestId,
				mailClaimedAt(batchRequestId, timestamp)
		);
		if (inserted != 1) {
			throw new IllegalStateException("초대 원장을 생성할 수 없습니다.");
		}
		return invitationId;
	}

	/**
	 * 일괄 등록 행만 적재 시점에 클레임한다. 단건 초대는 같은 트랜잭션 흐름에서 곧바로 발송·기록까지
	 * 끝나 안전망이 볼 일이 없고, 애초에 batch_request_id가 NULL이라 조회에 걸리지 않는다.
	 */
	private static Timestamp mailClaimedAt(String batchRequestId, Timestamp invitedAt) {
		return batchRequestId == null ? null : invitedAt;
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

}
