package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 기수 스코프가 <b>실제로 걸러내는지</b>를 본다. {@link JdbcManagerRosterRepositorySqlTest}는 SQL이
 * 파싱·실행되는지만 보므로, 조건이 통째로 빠져도 그쪽은 통과한다.
 *
 * <p>픽스처가 없으면 건너뛴다. {@link JdbcManagerRosterRepositorySqlTest}의 컨테이너를 띄워 스키마를
 * 올린 뒤:
 * <pre>
 *   docker exec -i pg-verify psql -U postgres -d checkdb \
 *     -f - &lt; src/test/resources/fixtures/manager-roster-cohort-scope-fixture.sql
 * </pre>
 * 픽스처 구성: 7기·6기 두 기수, 매니저 4명(M1=양쪽 배정, M2=6기만, M3=7기 초대만, M4=6기 초대+7기 배정).
 * 이 픽스처는 {@code TRUNCATE}로 시작하므로 다른 데이터가 든 DB에 올리지 않는다.
 */
class JdbcManagerRosterCohortScopeSqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
	private static final UUID COHORT_7 = UUID.fromString("00000000-0000-0000-0000-000000000071");

	@Test
	void listsOnlyTheManagersOfThatCohort() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		List<String> names = repository.findManagers(
						new ManagerRosterRepository.ManagerRosterCriteria(
								ORG_ID, COHORT_7, null, null, ManagerRosterSort.NAME),
						PageRequest.of(0, 20))
				.getContent().stream().map(ManagerRosterRepository.ManagerRosterRow::email).toList();

		// M2는 6기만 담당하므로 빠지고, M3는 반이 없어도 7기 초대가 있어 들어온다.
		assertThat(names).containsExactlyInAnyOrder("m1@green.com", "m3@green.com", "m4@green.com");
	}

	@Test
	void keepsEveryManagerInTheOrganizationWhenNoCohortIsGiven() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		List<String> names = repository.findManagers(
						new ManagerRosterRepository.ManagerRosterCriteria(
								ORG_ID, null, null, null, ManagerRosterSort.NAME),
						PageRequest.of(0, 20))
				.getContent().stream().map(ManagerRosterRepository.ManagerRosterRow::email).toList();

		assertThat(names).hasSize(4);
	}

	/** M1은 7기·6기 반을 모두 맡고 있다. 7기로 좁히면 6기 반과 그 인원이 섞이면 안 된다. */
	@Test
	void narrowsClassroomsAndTraineeCountToThatCohort() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		ManagerRosterRepository.ManagerRosterRow scoped = findByEmail(repository, COHORT_7, "m1@green.com");
		assertThat(scoped.classroomNames()).containsExactly("A반_7기");
		assertThat(scoped.assignedTraineeCount()).isEqualTo(2);

		ManagerRosterRepository.ManagerRosterRow unscoped = findByEmail(repository, null, "m1@green.com");
		assertThat(unscoped.classroomNames()).containsExactlyInAnyOrder("A반_7기", "A반_6기");
		assertThat(unscoped.assignedTraineeCount()).isEqualTo(3);
	}

	/** 화면 비고의 '2026-07-24 초대 · 김오퍼레이터'. 초대일과 초대자가 같은 초대 행에서 와야 한다. */
	@Test
	void readsTheInviterNameAlongsideTheInvitationDate() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		ManagerRosterRepository.ManagerRosterRow invited = findByEmail(repository, COHORT_7, "m3@green.com");

		assertThat(invited.invitedByName()).isEqualTo("김오퍼레이터");
		assertThat(invited.invitedAt()).isNotNull();
		assertThat(invited.classroomNames()).isEmpty();
		assertThat(invited.assignedTraineeCount()).isZero();
	}

	/** 상태 칩 합계가 목록 건수와 어긋나지 않으려면 집계도 같은 기수 범위여야 한다. */
	@Test
	void countsStatusesWithinTheCohortScope() {
		JdbcManagerRosterRepository repository = repositoryOrSkip();

		assertThat(repository.countByStatus(ORG_ID, COHORT_7).values().stream().mapToLong(Long::longValue).sum())
				.isEqualTo(3);
		assertThat(repository.countByStatus(ORG_ID, null).values().stream().mapToLong(Long::longValue).sum())
				.isEqualTo(4);
	}

	private ManagerRosterRepository.ManagerRosterRow findByEmail(
			JdbcManagerRosterRepository repository, UUID cohortId, String email
	) {
		return repository.findManagers(
						new ManagerRosterRepository.ManagerRosterCriteria(
								ORG_ID, cohortId, null, null, ManagerRosterSort.NAME),
						PageRequest.of(0, 20))
				.getContent().stream()
				.filter(row -> email.equals(row.email()))
				.findFirst()
				.orElseThrow(() -> new AssertionError(email + " 행이 없습니다."));
	}

	private JdbcManagerRosterRepository repositoryOrSkip() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		boolean ready;
		try (var connection = dataSource.getConnection();
			 var statement = connection.createStatement();
			 var resultSet = statement.executeQuery(
					 "SELECT COUNT(*) FROM app_user WHERE email = 'm1@green.com'")) {
			ready = resultSet.next() && resultSet.getInt(1) == 1;
		} catch (Exception exception) {
			ready = false;
		}
		assumeTrue(ready, "검증용 픽스처가 없어 건너뜁니다.");
		return new JdbcManagerRosterRepository(new JdbcTemplate(dataSource));
	}
}
