package com.bigproject.backend.domain.projectexecution.infrastructure;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 34차 R16③④로 새로 낸 {@code findCurriculumUsageStats}를 정본 스키마에 대고 실행한다.
 *
 * <p>{@code FULL OUTER JOIN}으로 두 집계를 합치고 {@code uuid[]} 배열 파라미터를 두 번 바인딩하는
 * 질의라, 컬럼명·배열 타입·조인 경로가 실행해 보기 전에는 확인되지 않는다.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcProjectDependencyRepositoryUsageSqlTest {
	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Test
	void usageStatsQueryParsesAndRunsAgainstTheCanonicalSchema() throws IOException, InterruptedException {
		JdbcProjectDependencyRepository repository = repositoryOrSkip();

		assertThat(repository.findCurriculumUsageStats(List.of(UUID.randomUUID()))).isEmpty();
		assertThat(repository.findCurriculumUsageStats(
				List.of(UUID.randomUUID(), UUID.randomUUID()))).isEmpty();
		// 빈 목록은 DB에 가지 않는다.
		assertThat(repository.findCurriculumUsageStats(List.of())).isEmpty();
	}

	private JdbcProjectDependencyRepository repositoryOrSkip() throws IOException, InterruptedException {
		Path ddlPath = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql").toAbsolutePath();
		Path viewsPath = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_View.sql").toAbsolutePath();
		assumeTrue(Files.exists(ddlPath) && Files.exists(viewsPath),
				"정본 DDL/View 문서가 없어 SQL 검증을 건너뜁니다.");

		postgres.copyFileToContainer(MountableFile.forHostPath(ddlPath), "/tmp/schema.sql");
		postgres.copyFileToContainer(MountableFile.forHostPath(viewsPath), "/tmp/views.sql");
		load("/tmp/schema.sql");
		load("/tmp/views.sql");

		var dataSource = new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		dataSource.setDriverClassName("org.postgresql.Driver");
		return new JdbcProjectDependencyRepository(new JdbcTemplate(dataSource));
	}

	private void load(String path) throws IOException, InterruptedException {
		var result = postgres.execInContainer("psql", "-U", postgres.getUsername(),
				"-d", postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", path);
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
	}
}
