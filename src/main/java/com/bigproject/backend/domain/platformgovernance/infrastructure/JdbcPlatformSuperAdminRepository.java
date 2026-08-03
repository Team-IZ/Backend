package com.bigproject.backend.domain.platformgovernance.infrastructure;

import com.bigproject.backend.domain.platformgovernance.domain.PlatformSuperAdminRepository;
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
			JOIN "role" r ON r.role_id = u.role_id
			WHERE u.deleted_at IS NULL
				AND r.code = ?
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
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND r.code = ?
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, SUPER_ADMIN_ROLE_CODE);
		return count == null ? 0 : count;
	}

	@Override
	public int updateStatus(UUID memberId, OperatorAccountStatus status) {
		// row_version은 낙관적 락 컬럼이라 직접 UPDATE에서도 함께 올려 준다.
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
