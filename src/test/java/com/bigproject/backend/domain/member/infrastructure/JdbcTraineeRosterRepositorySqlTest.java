package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 네이티브 SQL을 실제 PostgreSQL 스키마에 대고 실행해 본다(단위 테스트의 JdbcTemplate mock으로는
 * 컬럼 오타·조인 경로 오류가 잡히지 않는다).
 *
 * 임시 컨테이너가 없으면 건너뛴다.
 *   docker run -d --name pg-verify -p 55440:5432 \
 *     -e POSTGRES_PASSWORD=devcheck -e POSTGRES_DB=checkdb postgres:16
 *   docker exec pg-verify psql -U postgres -d checkdb \
 *     -f docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql
 *   docker exec pg-verify psql -U postgres -d checkdb \
 *     -f docs/table-definition/테이블정의서_v07_교육생홈_View.sql
 */
class JdbcTraineeRosterRepositorySqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private final UUID cohortId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();
	private final UUID classroomId = UUID.randomUUID();
	private final UUID traineeId = UUID.randomUUID();

	@Test
	void everyStatementParsesAndRunsAgainstTheRealSchema() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findCohortScope(cohortId)).doesNotThrowAnyException();
		assertThatCode(() -> repository.countUnassigned(cohortId, organizationId, null)).doesNotThrowAnyException();
		assertThatCode(() -> repository.findTrainee(traineeId, cohortId, organizationId))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.updateStatus(traineeId, "INACTIVE", UUID.randomUUID(),
				"ADMIN_SUSPENDED", "테스트 사유")).doesNotThrowAnyException();
		// GREATEST(now, joined_at + interval)와 CASE 분기가 실제 타입에 맞는지 양방향으로 확인한다.
		assertThatCode(() -> repository.updateCohortMembership(traineeId, cohortId, organizationId, true))
				.doesNotThrowAnyException();
		assertThatCode(() -> repository.updateCohortMembership(traineeId, cohortId, organizationId, false))
				.doesNotThrowAnyException();

		for (TraineeRosterSort sort : TraineeRosterSort.values()) {
			assertThatCode(() -> repository.findRoster(
					new TraineeRosterRepository.RosterCriteria(
							cohortId, organizationId, null, false, null, null, sort),
					PageRequest.of(0, 20))).doesNotThrowAnyException();
		}
	}

	@Test
	void findRosterParsesEveryFilterCombination() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.findRoster(
				new TraineeRosterRepository.RosterCriteria(
						cohortId, organizationId, classroomId, false, "ACTIVE", "강건우", TraineeRosterSort.NAME),
				PageRequest.of(0, 20))).doesNotThrowAnyException();

		assertThatCode(() -> repository.findRoster(
				new TraineeRosterRepository.RosterCriteria(
						cohortId, organizationId, null, true, "PENDING", null, TraineeRosterSort.RECENT_ENROLLED),
				PageRequest.of(1, 10))).doesNotThrowAnyException();
	}

	@Test
	void cohortWideCountsParseAndShareTheSamePopulation() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();

		assertThatCode(() -> repository.countCohortTotal(cohortId, organizationId, null)).doesNotThrowAnyException();
		assertThatCode(() -> repository.countUnassigned(cohortId, organizationId, null)).doesNotThrowAnyException();
		// 미배정은 기수 전체의 부분집합이라 어떤 데이터에서도 총원을 넘을 수 없다.
		assertThat(repository.countUnassigned(cohortId, organizationId, null))
				.isLessThanOrEqualTo(repository.countCohortTotal(cohortId, organizationId, null));
	}

	/**
	 * 30차 R7 — 계정 상태별 집계는 담당 반 조건이 붙고 안 붙고에 따라 <b>서로 다른 문장</b>이 된다
	 * (조각을 문자열로 이어 붙인다). 매니저 쪽을 돌려 보지 않으면 그 문장의 문법 오류나 바인딩
	 * 어긋남을 전혀 보지 못한다.
	 *
	 * <p>합이 총원과 같은지도 같은 데이터에서 확인한다 — 두 질의의 WHERE가 갈리면 화면 머리글의
	 * `활성 24 · 초대 대기 1 · 비활성 1`이 총원과 맞지 않게 되고, 그 어긋남은 화면에서만 보인다.
	 */
	@Test
	void accountStatusCountsParseForBothRolesAndAddUpToTheTotal() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();
		UUID managerUserId = UUID.randomUUID();

		assertThatCode(() -> repository.countByAccountStatus(cohortId, organizationId, managerUserId))
				.doesNotThrowAnyException();

		TraineeRosterRepository.AccountStatusCounts counts =
				repository.countByAccountStatus(cohortId, organizationId, null);
		assertThat(counts.activeCount() + counts.invitedCount() + counts.inactiveCount())
				.isEqualTo(repository.countCohortTotal(cohortId, organizationId, null));
	}

	private JdbcTraineeRosterRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean reachable;
		try (var ignored = dataSource.getConnection()) {
			reachable = true;
		} catch (Exception exception) {
			reachable = false;
		}
		assumeTrue(reachable, "검증용 PostgreSQL 컨테이너가 없어 건너뜁니다.");
		return new JdbcTraineeRosterRepository(new JdbcTemplate(dataSource));
	}
}
