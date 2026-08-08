package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 네이티브 SQL을 실제 PostgreSQL 스키마에 대고 실행해 본다.
 *
 * 임시 컨테이너가 없으면 건너뛴다.
 *   docker run -d --name pg-verify -p 55440:5432 \
 *     -e POSTGRES_PASSWORD=devcheck -e POSTGRES_DB=checkdb postgres:16
 *   docker exec pg-verify psql -U postgres -d checkdb \
 *     -f docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql
 *   docker exec pg-verify psql -U postgres -d checkdb \
 *     -f docs/table-definition/테이블정의서_v07_교육생홈_View.sql
 */
class JdbcManagerRosterRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();

	@Test
	void everyStatementParsesAndRunsAgainstTheRealSchema() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		for (ManagerRosterSort sort : ManagerRosterSort.values()) {
			assertThatCode(() -> repository.findManagers(
					new ManagerRosterRepository.ManagerRosterCriteria(organizationId, null, null, null, sort),
					PageRequest.of(0, 20))).doesNotThrowAnyException();
		}
	}

	@Test
	void findManagersParsesEveryFilterCombination() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findManagers(
				new ManagerRosterRepository.ManagerRosterCriteria(
						organizationId, null, "ACTIVE", "강민서", ManagerRosterSort.ASSIGNED_TRAINEE_COUNT),
				PageRequest.of(0, 20))).doesNotThrowAnyException();

		assertThatCode(() -> repository.findManagers(
				new ManagerRosterRepository.ManagerRosterCriteria(
						organizationId, null, "PENDING", null, ManagerRosterSort.NAME),
				PageRequest.of(1, 10))).doesNotThrowAnyException();
	}

	/**
	 * 기수를 걸면 SELECT 절 서브쿼리와 WHERE 절 양쪽에 인자가 늘어난다. 위치 인자라 순서가 밀리면
	 * 조용히 엉뚱한 값이 바인딩되는 게 아니라 타입 불일치로 여기서 터진다.
	 */
	@Test
	void findManagersParsesWithTheCohortScopeApplied() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		for (ManagerRosterSort sort : ManagerRosterSort.values()) {
			assertThatCode(() -> repository.findManagers(
					new ManagerRosterRepository.ManagerRosterCriteria(organizationId, cohortId, null, null, sort),
					PageRequest.of(0, 20))).doesNotThrowAnyException();
		}

		assertThatCode(() -> repository.findManagers(
				new ManagerRosterRepository.ManagerRosterCriteria(
						organizationId, cohortId, "PENDING", "강민서", ManagerRosterSort.ASSIGNED_TRAINEE_COUNT),
				PageRequest.of(1, 10))).doesNotThrowAnyException();
	}

	@Test
	void countByStatusParsesAndGroupsByTheRawAccountStatus() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.countByStatus(organizationId, null)).doesNotThrowAnyException();
		assertThatCode(() -> repository.countByStatus(organizationId, cohortId)).doesNotThrowAnyException();
	}

	private JdbcManagerRosterRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcManagerRosterRepository(new JdbcTemplate(dataSource));
	}
}
