package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.PlatformSuperAdminRepository;
import com.bigproject.backend.domain.organization.domain.AccountInactivationReason;
import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
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

/**
 * 슈퍼어드민 계정 조회·상태 변경 (SA-03 ②).
 *
 * <p>슈퍼어드민 판정 조건은 <b>역할 코드가 SUPER_ADMIN</b>이다. {@code org_id IS NULL}까지 조건에 넣지 않는 이유는,
 * 기관에 소속된 SUPER_ADMIN 행이 데이터 오류로 생겼을 때 목록에서 숨겨 버리면 오히려 발견이 늦어지기 때문이다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcPlatformSuperAdminRepository implements PlatformSuperAdminRepository {

	private static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

	private static final String SELECT_SUPER_ADMINS = """
			SELECT u.user_id, u.name, u.email, u.status, u.last_login_at, u.created_at
			FROM app_user u
			WHERE u.deleted_at IS NULL
				AND u.role_code = ?
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<SuperAdminAccount> findSuperAdmins() {
		return jdbcTemplate.query(
				SELECT_SUPER_ADMINS + " ORDER BY u.created_at",
				(ResultSet rs, int rowNum) -> map(rs),
				SUPER_ADMIN_ROLE_CODE
		);
	}

	@Override
	public Optional<SuperAdminAccount> findSuperAdmin(UUID memberId) {
		return jdbcTemplate.query(
				SELECT_SUPER_ADMINS + " AND u.user_id = ?",
				(ResultSet rs, int rowNum) -> map(rs),
				SUPER_ADMIN_ROLE_CODE, memberId
		).stream().findFirst();
	}

	@Override
	public int countActiveSuperAdmins() {
		String sql = """
				SELECT COUNT(*)
				FROM app_user u
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND u.role_code = ?
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, SUPER_ADMIN_ROLE_CODE);
		return count == null ? 0 : count;
	}

	@Override
	public int updateStatus(
			UUID memberId,
			OperatorAccountStatus status,
			UUID inactivatedBy,
			AccountInactivationReason reasonCode,
			String reason
	) {
		/*
		 * ck_app_user_status_3: status='INACTIVE'이면 inactivated_at·inactivated_by·
		 * inactivated_reason_code가 모두 NOT NULL이어야 한다(오퍼레이터 정지와 같은 제약).
		 * row_version은 낙관적 락 컬럼이라 직접 UPDATE에서도 함께 올려 준다.
		 */
		boolean inactivating = status == OperatorAccountStatus.INACTIVE;
		String sql = """
				UPDATE app_user
				SET status = ?,
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
				status.name(),
				inactivating,
				inactivating, inactivatedBy,
				inactivating, reasonCode == null ? null : reasonCode.name(),
				inactivating, reason,
				memberId
		);
	}

	private SuperAdminAccount map(ResultSet rs) throws SQLException {
		return new SuperAdminAccount(
				rs.getObject("user_id", UUID.class),
				rs.getString("name"),
				rs.getString("email"),
				OperatorAccountStatus.valueOf(rs.getString("status")),
				toInstant(rs.getTimestamp("last_login_at")),
				toInstant(rs.getTimestamp("created_at"))
		);
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
