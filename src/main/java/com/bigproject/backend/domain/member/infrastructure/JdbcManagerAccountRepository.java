package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.ManagerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcManagerAccountRepository implements ManagerAccountRepository {

	/** {@code JdbcManagerRosterRepository}·{@code JdbcOrganizationOperatorRepository}의 동일 상수와 항상 같은 값이어야 한다. */
	private static final String MANAGER_INVITE_PURPOSE = "INVITE_OPERATOR_MANAGER";

	private static final String MANAGER_ROLE_CODE = "MANAGER";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<ManagerAccount> findManager(UUID orgId, UUID managerId) {
		String sql = """
				SELECT u.user_id, u.status
				FROM app_user u
				WHERE u.deleted_at IS NULL
					AND u.org_id = ?
					AND u.role_code = ?
					AND u.user_id = ?
				""";
		List<ManagerAccount> found = jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new ManagerAccount(
						rs.getObject("user_id", UUID.class), rs.getString("status")),
				orgId, MANAGER_ROLE_CODE, managerId);
		return found.stream().findFirst();
	}

	/**
	 * CHECK 두 개를 함께 만족시켜야 한다 — {@code JdbcOrganizationOperatorRepository.updateOperatorStatus}와
	 * 같은 SQL이다(같은 테이블·같은 제약이라 규칙이 갈리면 안 된다).
	 *
	 * <p>{@code ck_app_user_status_3}: INACTIVE면 inactivated_at·inactivated_by·inactivated_reason_code가
	 * 모두 NOT NULL이어야 한다. ACTIVE로 되돌릴 때는 반대로 비운다 — 활성 계정에 정지 이력이 남아 있으면 모순이다.
	 *
	 * <p>{@code ck_app_user_status_2}: PENDING이 아니면 name이 NOT NULL이어야 한다. 초대만 받은 자리는
	 * 이름이 비어 있으므로(이름은 수락할 때 본인이 넣는다) 제약을 만족시킬 값을 하나 넣어야 한다.
	 *
	 * <h2>🔴 이메일 로컬파트가 아니라 빈 문자열이다(25차 R9)</h2>
	 *
	 * <p>종전에는 {@code split_part(email, '@', 1)}을 넣었다. "재초대가 덮어쓰니 화면에 남지 않는다"고
	 * 봤는데 <b>사실이 아니었다</b> — 초대를 취소하기만 하고 재초대하지 않으면 그 값이 그대로 남아,
	 * 이름 없이 초대한 매니저가 목록에서 {@code name: "nulltest-probe"}로 보였다.
	 *
	 * <p>그것은 <b>거짓을 사실처럼 만드는 값</b>이다. 이메일 조각이지 그 사람의 이름이 아닌데
	 * 목록에서 실명과 나란히 서면 구분되지 않고, 한 번 박히면 「이름을 모른다」는 사실이 사라진다.
	 *
	 * <p>빈 문자열은 CHECK를 만족시키면서도 <b>이름이 아니다.</b> 읽는 쪽
	 * ({@code JdbcManagerRosterRepository})이 {@code NULLIF(name, '')}로 되돌려 API는 {@code null}을 준다 —
	 * 이름이 없다는 사실이 그대로 보존된다. 표시는 화면의 몫이라 서버가 채울 필요가 없다.
	 */
	@Override
	public int updateManagerStatus(UUID managerId, String rawStatus, UUID inactivatedBy,
			String reasonCode, String reason) {
		boolean inactivating = "INACTIVE".equals(rawStatus);
		String sql = """
				UPDATE app_user
				SET status = ?,
				    name = CASE WHEN ? THEN COALESCE(name, '') ELSE name END,
				    inactivated_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
				    inactivated_by = CASE WHEN ? THEN ?::uuid ELSE NULL END,
				    inactivated_reason_code = CASE WHEN ? THEN ? ELSE NULL END,
				    inactivated_reason = CASE WHEN ? THEN ? ELSE NULL END,
				    updated_at = CURRENT_TIMESTAMP,
				    row_version = row_version + 1
				WHERE user_id = ?
					AND deleted_at IS NULL
				""";
		return jdbcTemplate.update(
				sql,
				rawStatus,
				inactivating,
				inactivating,
				inactivating, inactivatedBy,
				inactivating, reasonCode,
				inactivating, reason,
				managerId);
	}

	/**
	 * 초대 목적 코드가 오퍼레이터와 합쳐져 있어(v06 {@code INVITE_OPERATOR_MANAGER}) 토큰만으로는
	 * 매니저 초대인지 알 수 없다. 토큰 주인의 역할이 MANAGER인지까지 확인해야
	 * 오퍼레이터 초대를 이 경로로 취소·재발송하는 길이 막힌다.
	 */
	@Override
	public Optional<PendingManagerInvitation> findPendingInvitation(UUID orgId, UUID tokenId) {
		String sql = """
				SELECT t.token_id, t.user_id, t.invitation_id, t.target_email
				FROM one_time_token t
				JOIN app_user u ON u.user_id = t.user_id
				WHERE t.token_id = ?
					AND t.org_id = ?
					AND t.purpose = ?
					AND u.role_code = ?
					AND t.used_at IS NULL
					AND t.invalidated_at IS NULL
				""";
		List<PendingManagerInvitation> found = jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new PendingManagerInvitation(
						rs.getObject("token_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getObject("invitation_id", UUID.class),
						rs.getString("target_email")),
				tokenId, orgId, MANAGER_INVITE_PURPOSE, MANAGER_ROLE_CODE);
		return found.stream().findFirst();
	}

	@Override
	public int invalidateInvitation(UUID tokenId, String reasonCode) {
		String sql = """
				UPDATE one_time_token
				SET invalidated_at = CURRENT_TIMESTAMP,
				    invalidated_reason = ?
				WHERE token_id = ?
					AND used_at IS NULL
					AND invalidated_at IS NULL
				""";
		return jdbcTemplate.update(sql, reasonCode, tokenId);
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
}
