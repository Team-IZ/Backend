package com.bigproject.backend.domain.submission.infrastructure;

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
 * 34차 R8로 새로 낸 묶음 조회 두 개를 <b>정본 스키마에 대고 실제로 실행</b>한다.
 *
 * <p>서비스 테스트는 리포지토리를 목으로 대체하므로 이 SQL은 한 번도 파싱되지 않는다. 특히
 * {@code findManagerProgressAggregates}는 CTE 셋에 {@code IN} 자리표시자를 개수만큼 만들어
 * 붙이므로, <b>자리표시자 개수와 인자 개수가 어긋나는 것</b>이 실행해 보기 전에는 드러나지 않는다.
 * 회차를 1건·3건으로 바꿔 두 번 돌리는 이유가 그것이다.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcSubmissionStatusQueryRepositoryBatchSqlTest {
	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	private final UUID organizationId = UUID.randomUUID();
	private final UUID managerUserId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();

	@Test
	void batchedQueriesParseAndRunAgainstTheCanonicalSchema() throws IOException, InterruptedException {
		JdbcSubmissionStatusQueryRepository repository = repositoryOrSkip();

		assertThat(repository.findRounds(List.of(UUID.randomUUID()), 1)).isEmpty();
		assertThat(repository.findRounds(
				List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), 1)).isEmpty();

		// 자리표시자가 회차 수만큼 늘어난다. 인자 개수가 어긋나면 여기서 터진다.
		assertThat(repository.findManagerProgressAggregates(
				List.of(UUID.randomUUID()), organizationId, managerUserId, null)).isEmpty();
		assertThat(repository.findManagerProgressAggregates(
				List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
				organizationId, managerUserId, classId)).isEmpty();
	}

	/** 빈 목록은 DB에 가지 않는다 — {@code IN ()}은 문법 오류라 반드시 앞에서 끊어야 한다. */
	@Test
	void emptyInputNeverReachesTheDatabase() throws IOException, InterruptedException {
		JdbcSubmissionStatusQueryRepository repository = repositoryOrSkip();

		assertThat(repository.findRounds(List.of(), 1)).isEmpty();
		assertThat(repository.findManagerProgressAggregates(
				List.of(), organizationId, managerUserId, null)).isEmpty();
	}

	private JdbcSubmissionStatusQueryRepository repositoryOrSkip() throws IOException, InterruptedException {
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
		return new JdbcSubmissionStatusQueryRepository(new JdbcTemplate(dataSource));
	}

	private void load(String path) throws IOException, InterruptedException {
		var result = postgres.execInContainer("psql", "-U", postgres.getUsername(),
				"-d", postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", path);
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
	}
}
