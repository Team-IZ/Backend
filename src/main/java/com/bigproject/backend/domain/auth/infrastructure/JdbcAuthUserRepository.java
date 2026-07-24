package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAuthUserRepository implements AuthUserRepository {
	private static final String FIND_BY_NORMALIZED_EMAIL = """
			SELECT
				u.user_id,
				u.org_id,
				u.email,
				u.name,
				u.password_hash,
				u.status,
				u.is_email_verified,
				u.locked_until,
				r.code AS role_code,
				o.status AS organization_status
			FROM app_user u
			JOIN "role" r ON r.role_id = u.role_id
			LEFT JOIN organization o ON o.org_id = u.org_id
			WHERE u.normalized_email = ?
				AND u.deleted_at IS NULL
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<AuthUser> findByNormalizedEmail(String normalizedEmail) {
		return jdbcTemplate.query(
				FIND_BY_NORMALIZED_EMAIL,
				(rs, rowNum) -> {
					Timestamp lockedUntil = rs.getTimestamp("locked_until");
					return new AuthUser(
							rs.getObject("user_id", java.util.UUID.class),
							rs.getObject("org_id", java.util.UUID.class),
							rs.getString("email"),
							rs.getString("name"),
							rs.getString("password_hash"),
							rs.getString("status"),
							rs.getBoolean("is_email_verified"),
							lockedUntil == null ? null : lockedUntil.toInstant(),
							Role.valueOf(rs.getString("role_code")),
							rs.getString("organization_status")
					);
				},
				normalizedEmail
		).stream().findFirst();
	}

	@Override
	public void updateLastLoginAt(UUID userId, Instant lastLoginAt) {
		int updatedRows = jdbcTemplate.update(
				"""
				UPDATE app_user
				SET last_login_at = ?
				WHERE user_id = ?
					AND deleted_at IS NULL
				""",
				Timestamp.from(lastLoginAt),
				userId
		);
		if (updatedRows != 1) {
			throw new IllegalStateException("로그인 사용자의 최근 로그인 시각을 갱신할 수 없습니다.");
		}
	}
}
