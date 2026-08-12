package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.CohortComparisonQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 네이티브 SQL을 실제 PostgreSQL 스키마에 대고 실행해 본다.
 *
 * 단위 테스트는 JdbcTemplate을 목으로 대체하므로 SQL 문자열이 한 번도 파싱되지 않는다.
 * 컬럼 오타·조인 경로 오류·String.formatted 자리 수 불일치는 여기서만 잡힌다.
 *
 * 임시 컨테이너가 없으면 건너뛴다.
 *   docker run -d --name pg-verify -p 55440:5432 \
 *     -e POSTGRES_PASSWORD=devcheck -e POSTGRES_DB=checkdb postgres:16
 *   docker exec pg-verify psql -U postgres -d checkdb \
 *     -f docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql
 *   docker exec pg-verify psql -U postgres -d checkdb \
 *     -f docs/table-definition/테이블정의서_v07_교육생홈_View.sql
 */
class JdbcCohortComparisonQueryRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID baselineCohortId = UUID.randomUUID();

	@Test
	void everyStatementParsesAndRunsAgainstTheRealSchema() {
		JdbcCohortComparisonQueryRepository repository = repositoryOrSkip();
		List<UUID> cohortIds = List.of(cohortId, baselineCohortId);

		assertThatCode(() -> repository.findCohort(cohortId)).doesNotThrowAnyException();
		assertThatCode(() -> repository.findBaselineCandidates(organizationId, cohortId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.hasPublishedDiagnosis(cohortId, organizationId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.aggregateConceptLevels(organizationId, cohortIds))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.findConceptRounds(organizationId, cohortIds))
				.doesNotThrowAnyException();
	}

	/**
	 * 완전성 조회는 ACTIVE_SNAPSHOT_CTE에 컬럼 세 개를 더해 만든 것이라, CTE를 공유하는
	 * 다른 두 질의가 함께 깨지지 않는지가 확인 대상이다.
	 */
	@Test
	void readsSnapshotCompletionWithoutFilteringPartial() {
		JdbcCohortComparisonQueryRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findSnapshotCompletion(organizationId, List.of(cohortId, baselineCohortId)))
				.doesNotThrowAnyException();
		// 기수를 하나만 넘겨도 placeholders 자리 수가 맞아야 한다.
		assertThatCode(() -> repository.findSnapshotCompletion(organizationId, List.of(cohortId)))
				.doesNotThrowAnyException();
	}

	@Test
	void returnsNoCompletionRowWhenTheCohortHasNoActiveSnapshot() {
		JdbcCohortComparisonQueryRepository repository = repositoryOrSkip();

		List<CohortComparisonQueryRepository.SnapshotCompletionRow> rows =
				repository.findSnapshotCompletion(organizationId, List.of(cohortId));

		assertThat(rows).isEmpty();
	}

	private JdbcCohortComparisonQueryRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcCohortComparisonQueryRepository(new JdbcTemplate(dataSource));
	}
}
