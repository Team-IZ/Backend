package com.bigproject.backend.domain.manager;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Testcontainers(disabledWithoutDocker = true)
class ManagerViewPostgresIntegrationTest {
	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Test
	void canonicalDdlCreatesManagerViewsWithJsonbAndArrayColumns() throws IOException, InterruptedException {
		Path ddlPath = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql").toAbsolutePath();
		Path viewsPath = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_View.sql").toAbsolutePath();
		assumeTrue(Files.exists(ddlPath) && Files.exists(viewsPath),
				"정본 DDL/View 문서가 없어 PostgreSQL 통합 검증을 건너뜁니다.");

		postgres.copyFileToContainer(MountableFile.forHostPath(ddlPath), "/tmp/schema.sql");
		postgres.copyFileToContainer(MountableFile.forHostPath(viewsPath), "/tmp/views.sql");
		assertSuccess(postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d",
				postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", "/tmp/schema.sql"));
		assertSuccess(postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d",
				postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", "/tmp/views.sql"));

		var dataSource = new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		var jdbc = new JdbcTemplate(dataSource);
		Integer viewCount = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.views
				WHERE table_schema = 'public' AND table_name IN (
				  'assessment_round_attendance', 'manager_invalid_attempt_review_view',
				  'manager_interview_list_view', 'manager_class_heatmap_view',
				  'manager_team_heatmap_view', 'manager_trainee_heatmap_view',
				  'manager_retried_trainee_heatmap_view', 'manager_trainee_risk_view',
				  'manager_trainee_roster_view', 'manager_trainee_detail_timeline_view')
				""", Integer.class);
		assertThat(viewCount).isEqualTo(10);
		assertThat(jdbc.queryForObject("SELECT jsonb_build_array(1, 2)::text", String.class))
				.isEqualTo("[1, 2]");
		assertThat(jdbc.queryForObject("SELECT array_to_string(ARRAY['RISK','EXCELLENT'], ',')", String.class))
				.isEqualTo("RISK,EXCELLENT");
	}

	private void assertSuccess(org.testcontainers.containers.Container.ExecResult result) {
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
	}
}
