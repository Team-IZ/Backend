package com.bigproject.backend.domain.projectexecution.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 현황 질의를 실제 PostgreSQL 스키마에 대고 실행한다.
 *
 * 이 질의들은 assessment_round_attendance View 를 읽으므로 테이블 DDL 뿐 아니라
 * View DDL 까지 올라간 컨테이너가 필요하다.
 *   docker run -d --name pg-verify -p 55440:5432 \
 *     -e POSTGRES_PASSWORD=devcheck -e POSTGRES_DB=checkdb postgres:16
 *   docker exec pg-verify psql -U postgres -d checkdb -f .../_DDL.sql
 *   docker exec pg-verify psql -U postgres -d checkdb -f .../_View.sql
 */
class JdbcClassProgressQueryRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID projectId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();

	@Test
	void everyStatementParsesAndRunsAgainstTheRealSchema() {
		JdbcClassProgressQueryRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findRound(projectId, 1)).doesNotThrowAnyException();
		assertThatCode(() -> repository.findClassProgress(roundId, organizationId)).doesNotThrowAnyException();
		assertThatCode(() -> repository.findConceptMatches(roundId, organizationId)).doesNotThrowAnyException();
	}

	@Test
	void returnsNothingForARoundThatDoesNotExist() {
		JdbcClassProgressQueryRepository repository = repositoryOrSkip();

		assertThat(repository.findRound(projectId, 1)).isEmpty();
		assertThat(repository.findClassProgress(roundId, organizationId)).isEmpty();
	}

	private JdbcClassProgressQueryRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcClassProgressQueryRepository(new JdbcTemplate(dataSource));
	}
}
