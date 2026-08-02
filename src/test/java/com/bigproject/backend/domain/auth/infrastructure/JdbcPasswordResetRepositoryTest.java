package com.bigproject.backend.domain.auth.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPasswordResetRepositoryTest {
	@Test
	void locksResetTokenUpdatesPasswordAndRevokesSessions() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"sa",
				""
		);
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("""
				CREATE TABLE app_user (
					user_id UUID PRIMARY KEY,
					password_hash VARCHAR(128),
					status VARCHAR(30) NOT NULL,
					password_changed_at TIMESTAMP WITH TIME ZONE,
					failed_login_count INTEGER NOT NULL,
					login_blocked_until TIMESTAMP WITH TIME ZONE,
					updated_at TIMESTAMP WITH TIME ZONE,
					row_version INTEGER NOT NULL,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE one_time_token (
					token_id UUID PRIMARY KEY,
					user_id UUID,
					org_id UUID,
					target_email VARCHAR(320) NOT NULL,
					target_email_normalized VARCHAR(320) NOT NULL,
					token_hash VARCHAR(128) NOT NULL,
					purpose VARCHAR(50) NOT NULL,
					expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
					used_at TIMESTAMP WITH TIME ZONE,
					used_request_id VARCHAR(100),
					invalidated_at TIMESTAMP WITH TIME ZONE,
					invalidated_reason VARCHAR(100)
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE refresh_token (
					token_id UUID PRIMARY KEY,
					user_id UUID NOT NULL,
					revoked_at TIMESTAMP WITH TIME ZONE,
					revoked_reason VARCHAR(100)
				)
				""");

		UUID userId = UUID.randomUUID();
		UUID tokenId = UUID.randomUUID();
		UUID refreshTokenId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, 'old-hash', 'ACTIVE', CURRENT_TIMESTAMP, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, NULL)",
				userId
		);
		jdbcTemplate.update(
				"INSERT INTO one_time_token VALUES (?, ?, NULL, 'user@example.com', 'user@example.com', 'token-hash', 'PASSWORD_RESET', ?, NULL, NULL, NULL, NULL)",
				tokenId,
				userId,
				Instant.now().plusSeconds(300)
		);
		jdbcTemplate.update("INSERT INTO refresh_token VALUES (?, ?, NULL, NULL)", refreshTokenId, userId);

		JdbcPasswordResetRepository repository = new JdbcPasswordResetRepository(jdbcTemplate);
		var token = repository.findTokenForUpdate("token-hash");
		Instant changedAt = Instant.now();
		boolean passwordUpdated = repository.updatePassword(userId, "new-hash", changedAt);
		repository.revokeAllRefreshTokens(userId, changedAt);

		assertThat(token).hasValueSatisfying(found -> {
			assertThat(found.tokenId()).isEqualTo(tokenId);
			assertThat(found.userStatus()).isEqualTo("ACTIVE");
		});
		assertThat(passwordUpdated).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT password_hash FROM app_user WHERE user_id = ?",
				String.class,
				userId
		)).isEqualTo("new-hash");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT revoked_reason FROM refresh_token WHERE token_id = ?",
				String.class,
				refreshTokenId
		)).isEqualTo("PASSWORD_CHANGED");
	}
}
