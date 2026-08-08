package com.bigproject.backend.domain.analytics.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 조치 필요 경보 네 질의를 실제 PostgreSQL 스키마에 대고 실행한다.
 *
 * 서비스 테스트는 리포지토리를 목으로 대체하므로 SQL이 한 번도 파싱되지 않는다.
 * 컬럼 오타·조인 경로 오류는 여기서만 잡힌다. 컨테이너 준비는
 * JdbcRiskTraineeQueryRepositorySqlTest 주석 참고.
 */
class JdbcOperationalActionQueryRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID cohortId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();

	@Test
	void everyAlertQueryParsesAndRunsAgainstTheRealSchema() {
		JdbcOperationalActionQueryRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findUnassignedClasses(cohortId, organizationId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.findConceptGaps(cohortId, organizationId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.findGroupGaps(cohortId, organizationId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.findInterviewBacklogs(cohortId, organizationId))
				.doesNotThrowAnyException();
	}

	@Test
	void returnsNothingForACohortThatDoesNotExist() {
		JdbcOperationalActionQueryRepository repository = repositoryOrSkip();

		assertThat(repository.findUnassignedClasses(cohortId, organizationId)).isEmpty();
		assertThat(repository.findGroupGaps(cohortId, organizationId)).isEmpty();
	}

	private JdbcOperationalActionQueryRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcOperationalActionQueryRepository(new JdbcTemplate(dataSource));
	}
}
