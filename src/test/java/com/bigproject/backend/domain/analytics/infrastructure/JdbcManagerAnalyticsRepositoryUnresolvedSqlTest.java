package com.bigproject.backend.domain.analytics.infrastructure;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 34차 R2·R3로 새로 낸 {@code findUnresolved}를 <b>정본 스키마에 대고 실제로 실행</b>한다.
 *
 * <p>서비스 테스트는 리포지토리를 목으로 대체하므로 SQL이 한 번도 파싱되지 않는다. 이 질의는
 * 계층마다 그룹 키·이름 조인을 문자열로 갈아 끼우고({@code %1$s}~{@code %3$s}) 매니저 스코프
 * 조인까지 얹으므로, <b>컬럼 오타·별칭 충돌·자리표시자 개수 불일치</b>가 실행해 보기 전에는
 * 드러나지 않는다. 세 계층을 모두 돌려 그것만 확인한다.
 *
 * <p>형제 {@code *SqlTest}들은 로컬 검증용 컨테이너({@code localhost:55440})를 쓰는데, 그것이
 * 떠 있지 않은 환경에서는 통째로 건너뛴다. 이 질의는 이번에 새로 낸 것이라 한 번은 반드시
 * 실행돼야 해서 컨테이너를 직접 띄운다 — {@code ManagerViewPostgresIntegrationTest}와 같은
 * 방식이고, 도커가 없으면 그때는 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcManagerAnalyticsRepositoryUnresolvedSqlTest {
	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	private final UUID managerId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID classroomId = UUID.randomUUID();
	private final UUID teamId = UUID.randomUUID();

	@Test
	void everyLevelParsesAndRunsAgainstTheCanonicalSchema() throws IOException, InterruptedException {
		JdbcManagerAnalyticsRepository repository = repositoryOrSkip();

		// 계층마다 그룹 키와 이름 조인이 다르다. 셋 다 돌려야 갈아 끼운 자리가 전부 검증된다.
		assertThat(repository.findUnresolved(
				managerId, cohortId, projectId, roundId, "CLASS", null, null)).isEmpty();
		assertThat(repository.findUnresolved(
				managerId, cohortId, projectId, roundId, "TEAM", classroomId, null)).isEmpty();
		assertThat(repository.findUnresolved(
				managerId, cohortId, projectId, roundId, "TRAINEE", classroomId, teamId)).isEmpty();
	}

	/** 계층 값이 셋 중 하나가 아니면 질의하지 않는다 — 문자열을 그대로 SQL에 끼우는 자리라 중요하다. */
	@Test
	void unknownLevelDoesNotReachTheDatabase() throws IOException, InterruptedException {
		JdbcManagerAnalyticsRepository repository = repositoryOrSkip();

		assertThat(repository.findUnresolved(
				managerId, cohortId, projectId, roundId, "SOMETHING_ELSE", null, null)).isEmpty();
	}

	private JdbcManagerAnalyticsRepository repositoryOrSkip() throws IOException, InterruptedException {
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
		return new JdbcManagerAnalyticsRepository(new JdbcTemplate(dataSource));
	}

	private void load(String path) throws IOException, InterruptedException {
		var result = postgres.execInContainer("psql", "-U", postgres.getUsername(),
				"-d", postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", path);
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
	}
}
