package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuthUserRepositoryTest {
	@Test
	void mapsOperatorRoleFromDatabaseDuringLoginLookup() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"sa",
				""
		);
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("CREATE TABLE organization (org_id UUID PRIMARY KEY, status VARCHAR(30) NOT NULL)");
		jdbcTemplate.execute("CREATE TABLE \"role\" (role_id UUID PRIMARY KEY, code VARCHAR(30) NOT NULL)");
		jdbcTemplate.execute("""
				CREATE TABLE app_user (
					user_id UUID PRIMARY KEY,
					org_id UUID,
					role_id UUID NOT NULL,
					email VARCHAR(320) NOT NULL,
					normalized_email VARCHAR(320) NOT NULL,
					name VARCHAR(200) NOT NULL,
					password_hash VARCHAR(255) NOT NULL,
					status VARCHAR(30) NOT NULL,
					is_email_verified BOOLEAN NOT NULL,
					login_blocked_until TIMESTAMP WITH TIME ZONE,
					password_changed_at TIMESTAMP WITH TIME ZONE,
					last_login_at TIMESTAMP WITH TIME ZONE,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");

		UUID organizationId = UUID.randomUUID();
		UUID roleId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO organization VALUES (?, 'ACTIVE')", organizationId);
		jdbcTemplate.update("INSERT INTO \"role\" VALUES (?, 'OPERATOR')", roleId);
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', TRUE, NULL, CURRENT_TIMESTAMP, NULL, NULL)",
				userId,
				organizationId,
				roleId,
				"operator@example.com",
				"operator@example.com",
				"Operator",
				"encoded-password"
		);

		var user = new JdbcAuthUserRepository(jdbcTemplate)
				.findByNormalizedEmail("operator@example.com");

		assertThat(user).hasValueSatisfying(found -> {
			assertThat(found.userId()).isEqualTo(userId);
			assertThat(found.role()).isEqualTo(Role.OPERATOR);
			assertThat(found.organizationStatus()).isEqualTo("ACTIVE");
		});
	}
}
