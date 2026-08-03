package com.bigproject.backend.domain.auth.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLoginCohortRepositoryTest {
	@Test
	void findsLatestOrganizationCohortAndLatestManagerAssignedCohort() {
		JdbcTemplate jdbcTemplate = jdbcTemplate();
		createSchema(jdbcTemplate);
		UUID organizationId = UUID.randomUUID();
		UUID managerUserId = UUID.randomUUID();
		UUID olderCohortId = insertCohort(
				jdbcTemplate,
				organizationId,
				"6기",
				LocalDate.of(2026, 1, 1),
				Instant.parse("2026-01-01T00:00:00Z")
		);
		insertCohort(
				jdbcTemplate,
				organizationId,
				"7기",
				LocalDate.of(2026, 7, 1),
				Instant.parse("2026-07-01T00:00:00Z")
		);
		UUID classId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO \"class\" VALUES (?, ?, ?, 'ACTIVE', NULL)",
				classId,
				organizationId,
				olderCohortId
		);
		jdbcTemplate.update(
				"INSERT INTO manager_assignment VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)",
				UUID.randomUUID(),
				managerUserId,
				organizationId,
				classId,
				Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
		);
		JdbcLoginCohortRepository repository = new JdbcLoginCohortRepository(jdbcTemplate);

		assertThat(repository.findLatestNameForOrganization(organizationId)).contains("7기");
		assertThat(repository.findLatestAssignedNameForManager(
				organizationId,
				managerUserId,
				Instant.parse("2026-08-03T00:00:00Z")
		)).contains("6기");
	}

	private JdbcTemplate jdbcTemplate() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
				"sa",
				""
		);
		return new JdbcTemplate(dataSource);
	}

	private void createSchema(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("""
				CREATE TABLE cohort (
					cohort_id UUID PRIMARY KEY,
					org_id UUID NOT NULL,
					name VARCHAR(200) NOT NULL,
					start_date DATE NOT NULL,
					created_at TIMESTAMP WITH TIME ZONE NOT NULL,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE "class" (
					class_id UUID PRIMARY KEY,
					org_id UUID NOT NULL,
					cohort_id UUID NOT NULL,
					lifecycle_status VARCHAR(30) NOT NULL,
					deleted_at TIMESTAMP WITH TIME ZONE
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE manager_assignment (
					assignment_id UUID PRIMARY KEY,
					manager_user_id UUID NOT NULL,
					org_id UUID NOT NULL,
					class_id UUID NOT NULL,
					status VARCHAR(30) NOT NULL,
					assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
					unassigned_at TIMESTAMP WITH TIME ZONE
				)
				""");
	}

	private UUID insertCohort(
			JdbcTemplate jdbcTemplate,
			UUID organizationId,
			String name,
			LocalDate startDate,
			Instant createdAt
	) {
		UUID cohortId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO cohort VALUES (?, ?, ?, ?, ?, NULL)",
				cohortId,
				organizationId,
				name,
				startDate,
				Timestamp.from(createdAt)
		);
		return cohortId;
	}
}
