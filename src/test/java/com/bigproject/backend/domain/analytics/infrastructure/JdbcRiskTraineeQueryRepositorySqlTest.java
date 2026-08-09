package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
class JdbcRiskTraineeQueryRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID cohortId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();

	@Test
	void everyStatementParsesAndRunsAgainstTheRealSchema() {
		JdbcRiskTraineeQueryRepository repository = repositoryOrSkip();

		for (UUID scopedProjectId : new UUID[]{null, projectId}) {
			RiskTraineeQueryRepository.RoundCriteria criteria =
					new RiskTraineeQueryRepository.RoundCriteria(
							cohortId, organizationId, "MINI_PROJECT", scopedProjectId, 1, 3);

			assertThatCode(() -> repository.findRounds(criteria)).doesNotThrowAnyException();
			assertThatCode(() -> repository.countRegisteredRounds(criteria)).doesNotThrowAnyException();
			assertThatCode(() -> repository.aggregateRiskCells(criteria)).doesNotThrowAnyException();
		}

		assertThatCode(() -> repository.findCohortScope(cohortId)).doesNotThrowAnyException();
		assertThatCode(() -> repository.findClassRosters(cohortId, organizationId)).doesNotThrowAnyException();
		assertThatCode(() -> repository.findCohortRoster(cohortId, organizationId)).doesNotThrowAnyException();
		assertThatCode(() -> repository.classroomBelongsToCohort(UUID.randomUUID(), cohortId, organizationId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.projectBelongsToCohort(projectId, cohortId, organizationId, "MINI_PROJECT"))
				.doesNotThrowAnyException();
	}

	@Test
	void teamLevelStatementsParseToo() {
		JdbcRiskTraineeQueryRepository repository = repositoryOrSkip();
		UUID classroomId = UUID.randomUUID();

		// 팀 격자는 projectId가 있어야 호출되므로 좁힌 criteria로만 확인한다.
		RiskTraineeQueryRepository.RoundCriteria criteria =
				new RiskTraineeQueryRepository.RoundCriteria(
						cohortId, organizationId, "MINI_PROJECT", projectId, 1, 3);

		assertThatCode(() -> repository.aggregateTeamRiskCells(criteria, classroomId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.findTeamRosters(projectId, classroomId, organizationId))
				.doesNotThrowAnyException();
	}

	@Test
	void narrowsRoundScopeToTheGivenProject() {
		JdbcRiskTraineeQueryRepository repository = repositoryOrSkip();

		// 데이터가 없으므로 결과는 비어 있지만, 프로젝트 조건이 붙은 SQL이 실제로 실행되는지 확인한다.
		assertThat(repository.countRegisteredRounds(new RiskTraineeQueryRepository.RoundCriteria(
				cohortId, organizationId, "MINI_PROJECT", projectId, 1, 3))).isZero();
	}

	private JdbcRiskTraineeQueryRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcRiskTraineeQueryRepository(new JdbcTemplate(dataSource));
	}
}
