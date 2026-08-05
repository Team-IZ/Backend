package com.bigproject.backend.domain.organization.infrastructure;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import com.bigproject.backend.domain.organization.domain.OrganizationOperatorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcOrganizationOperatorRepository implements OrganizationOperatorRepository {

	/**
	 * v06에서 role.code CHECK가 ('SUPER_ADMIN','OPERATOR','MANAGER','TRAINEE')로 바뀌어
	 * '총괄 매니저(LEAD_MANAGER)'가 '오퍼레이터(OPERATOR)'로 정리됐다.
	 * {@link JdbcOrganizationStatsRepository}의 동일 상수와 항상 같은 값이어야 한다.
	 */
	private static final String OPERATOR_ROLE_CODE = "OPERATOR";

	/**
	 * one_time_token.purpose CHECK (v06):
	 * IN ('INVITE_OPERATOR_MANAGER','INVITE_TRAINEE','EMAIL_VERIFY','PASSWORD_RESET').
	 * 오퍼레이터·매니저 초대가 한 목적 코드로 합쳐졌다.
	 */
	private static final String OPERATOR_INVITE_PURPOSE = "INVITE_OPERATOR_MANAGER";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<OperatorAccount> findOperators(UUID organizationId) {
		return jdbcTemplate.query(
				selectOperators() + " ORDER BY u.created_at",
				(ResultSet rs, int rowNum) -> mapOperator(rs),
				OPERATOR_INVITE_PURPOSE, OPERATOR_INVITE_PURPOSE, OPERATOR_INVITE_PURPOSE,
				organizationId, OPERATOR_ROLE_CODE
		);
	}

	@Override
	public Optional<OperatorAccount> findOperator(UUID organizationId, UUID memberId) {
		List<OperatorAccount> found = jdbcTemplate.query(
				selectOperators() + " AND u.user_id = ?",
				(ResultSet rs, int rowNum) -> mapOperator(rs),
				OPERATOR_INVITE_PURPOSE, OPERATOR_INVITE_PURPOSE, OPERATOR_INVITE_PURPOSE,
				organizationId, OPERATOR_ROLE_CODE, memberId
		);
		return found.stream().findFirst();
	}

	@Override
	public int countActiveOperators(UUID organizationId) {
		String sql = """
				SELECT COUNT(*) AS cnt
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND u.org_id = ?
					AND r.code = ?
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, organizationId, OPERATOR_ROLE_CODE);
		return count == null ? 0 : count;
	}

	@Override
	public int updateOperatorStatus(UUID memberId, OperatorAccountStatus status) {
		// row_version은 낙관적 락 컬럼이라 JPA가 아닌 직접 UPDATE에서도 함께 올려 준다.
		String sql = """
				UPDATE app_user
				SET status = ?,
				    updated_at = CURRENT_TIMESTAMP,
				    row_version = row_version + 1
				WHERE user_id = ?
					AND deleted_at IS NULL
				""";
		return jdbcTemplate.update(sql, status.name(), memberId);
	}

	@Override
	public Optional<PendingOperatorInvitation> findPendingInvitation(UUID organizationId, UUID tokenId) {
		String sql = """
				SELECT t.token_id, t.user_id, t.invitation_id, t.target_email
				FROM one_time_token t
				WHERE t.token_id = ?
					AND t.org_id = ?
					AND t.purpose = ?
					AND t.used_at IS NULL
					AND t.invalidated_at IS NULL
				""";
		List<PendingOperatorInvitation> found = jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new PendingOperatorInvitation(
						rs.getObject("token_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getObject("invitation_id", UUID.class),
						rs.getString("target_email")
				),
				tokenId, organizationId, OPERATOR_INVITE_PURPOSE
		);
		return found.stream().findFirst();
	}

	@Override
	public int invalidateInvitation(UUID tokenId, String reason) {
		String sql = """
				UPDATE one_time_token
				SET invalidated_at = CURRENT_TIMESTAMP,
				    invalidated_reason = ?
				WHERE token_id = ?
					AND used_at IS NULL
					AND invalidated_at IS NULL
				""";
		return jdbcTemplate.update(sql, reason, tokenId);
	}

	@Override
	public int cancelInvitationLedger(UUID invitationId, UUID cancelledBy) {
		// ck_user_invitation_updated_at_4: status='CANCELLED'이면 cancelled_at·cancelled_by가 NOT NULL이어야 한다.
		// 이미 수락(ACCEPTED)·만료(EXPIRED)된 초대는 되돌리지 않는다.
		String sql = """
				UPDATE user_invitation
				SET status = 'CANCELLED',
				    cancelled_at = CURRENT_TIMESTAMP,
				    cancelled_by = ?,
				    updated_at = CURRENT_TIMESTAMP
				WHERE invitation_id = ?
					AND status IN ('PENDING', 'SENT', 'DELIVERY_FAILED')
				""";
		return jdbcTemplate.update(sql, cancelledBy, invitationId);
	}

	/**
	 * 오퍼레이터 목록/단건 공통 SELECT.
	 *
	 * <p>초대일은 이 계정에 발급된 초대 토큰 중 가장 이른 issued_at을 쓴다(목업의 `초대일` = 처음 초대된 날).
	 * 대기 중 토큰 ID는 취소 버튼이 어떤 토큰을 지목해야 하는지 프론트가 알 수 있게 함께 내려준다.
	 * 상관 서브쿼리로 분리한 이유는 토큰을 조인하면 계정 한 명이 토큰 수만큼 중복 행으로 늘어나기 때문이다.
	 *
	 * <p>파라미터 순서: purpose(초대일), purpose(대기 토큰), org_id, role_code [, user_id]
	 */
	private String selectOperators() {
		return """
				SELECT u.user_id,
				       u.name,
				       u.email,
				       u.status,
				       u.last_login_at,
				       (SELECT MIN(t.issued_at) FROM one_time_token t
				         WHERE t.user_id = u.user_id AND t.purpose = ?) AS invited_at,
				       (SELECT t.token_id FROM one_time_token t
				         WHERE t.user_id = u.user_id AND t.purpose = ?
				           AND t.used_at IS NULL AND t.invalidated_at IS NULL
				         ORDER BY t.issued_at DESC
				         LIMIT 1) AS pending_token_id,
				       /*
				        * 가장 최근 초대가 메일 발송에 실패했는지. 목업 case 4·5의 [재발송] 배지 근거다.
				        *
				        * 지난 초대 중 하나라도 실패했는지가 아니라 <b>가장 최근 것</b>을 본다 —
				        * 실패 후 다시 초대해 성공했는데도 실패 배지가 영원히 남으면 안 된다.
				        * user_invitation은 계정을 직접 참조하지 않아 one_time_token으로 이어 붙인다.
				        */
				       COALESCE((SELECT ui.status FROM user_invitation ui
				                  JOIN one_time_token it ON it.invitation_id = ui.invitation_id
				                 WHERE it.user_id = u.user_id AND it.purpose = ?
				                 ORDER BY ui.invited_at DESC
				                 LIMIT 1) = 'DELIVERY_FAILED', FALSE) AS invitation_delivery_failed
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.org_id = ?
					AND r.code = ?
				""";
	}

	private OperatorAccount mapOperator(ResultSet rs) throws SQLException {
		return new OperatorAccount(
				rs.getObject("user_id", UUID.class),
				rs.getString("name"),
				rs.getString("email"),
				OperatorAccountStatus.valueOf(rs.getString("status")),
				toInstant(rs.getTimestamp("invited_at")),
				toInstant(rs.getTimestamp("last_login_at")),
				rs.getObject("pending_token_id", UUID.class),
				rs.getBoolean("invitation_delivery_failed")
		);
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
