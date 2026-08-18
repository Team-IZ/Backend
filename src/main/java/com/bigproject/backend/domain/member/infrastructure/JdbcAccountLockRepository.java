package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.AccountLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAccountLockRepository implements AccountLockRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<LockTarget> findUser(UUID organizationId, UUID userId) {
		String sql = """
				SELECT u.user_id, u.org_id, u.email::text AS email, u.name, r.code AS role_code,
					u.status, u.login_blocked_until
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.org_id = ?
					AND u.user_id = ?
				""";
		List<LockTarget> found = jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new LockTarget(
						rs.getObject("user_id", UUID.class),
						rs.getObject("org_id", UUID.class),
						rs.getString("email"),
						rs.getString("name"),
						rs.getString("role_code"),
						rs.getString("status"),
						toInstant(rs.getTimestamp("login_blocked_until"))
				),
				organizationId, userId);
		return found.stream().findFirst();
	}

	/**
	 * 해제({@code lockedUntil == null})일 때 {@code failed_login_count}를 함께 0으로 되돌린다.
	 * 카운터를 남겨 두면 풀어 준 계정이 다음 실패 한 번에 다시 막혀 "해제했다"는 말이 사실이 아니게 된다.
	 */
	@Override
	public int updateLoginBlockedUntil(UUID userId, Instant lockedUntil) {
		String sql = """
				UPDATE app_user
				SET login_blocked_until = ?,
					failed_login_count = CASE WHEN ?::timestamptz IS NULL THEN 0 ELSE failed_login_count END,
					updated_at = CURRENT_TIMESTAMP,
					row_version = row_version + 1
				WHERE user_id = ?
					AND deleted_at IS NULL
				""";
		Timestamp value = lockedUntil == null ? null : Timestamp.from(lockedUntil);
		return jdbcTemplate.update(sql, value, value, userId);
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
