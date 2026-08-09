package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 상태 변경이 실제로 무엇을 쓰는지 확인한다. 파싱만 보는 {@link JdbcTraineeRosterRepositorySqlTest}와 달리
 * 비활성화 사유와 중도 이탈 시각이 진짜로 저장·조회되는지 본다.
 *
 * <p>픽스처가 없으면 건너뛴다. 준비 방법은 {@link JdbcManagerRosterCohortScopeSqlTest} 참고.
 */
class JdbcTraineeStatusWriteSqlTest {
	private static final String URL = "jdbc:postgresql://localhost:55440/checkdb";

	private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
	private static final UUID COHORT_7 = UUID.fromString("00000000-0000-0000-0000-000000000071");
	private static final UUID TRAINEE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

	/** 명단 조회가 비활성화 사유를 실제로 읽어 오는지. */
	@Test
	void readsBackTheInactivationReason() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();
		suspend(repository);

		TraineeRosterRepository.RosterRow row = find(repository);

		assertThat(row.rawAccountStatus()).isEqualTo("INACTIVE");
		assertThat(row.inactivatedReasonCode()).isEqualTo("ADMIN_SUSPENDED");
		assertThat(row.inactivatedReason()).isEqualTo("중도 이탈 처리");
		assertThat(row.inactivatedAt()).isNotNull();
		// 조치한 사람은 ID뿐 아니라 화면에 쓸 이름까지 조인해서 내려간다.
		assertThat(row.inactivatedById()).isEqualTo(ACTOR);
		assertThat(row.inactivatedByName()).isEqualTo("김오퍼레이터");

		reactivate(repository);
	}

	/** 상태 변경이 cohort_member.left_at을 채우는지. 화면의 '중도 이탈 {날짜}' 비고가 이 값이다. */
	@Test
	void recordsTheLeftAtTimestampWhenSuspending() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();
		suspend(repository);

		TraineeRosterRepository.RosterRow row = find(repository);

		assertThat(row.leftAt()).isNotNull();
		assertThat(row.leftAt()).isAfter(row.joinedAt());
		assertThat(cohortMemberStatus(repository)).isEqualTo("LEFT");

		reactivate(repository);
	}

	/** ACTIVE면 left_at IS NULL이어야 한다 — 한쪽만 되돌리면 불변식이 깨진다. */
	@Test
	void clearsTheLeftAtTimestampWhenReactivating() {
		JdbcTraineeRosterRepository repository = repositoryOrSkip();
		suspend(repository);
		reactivate(repository);

		TraineeRosterRepository.RosterRow row = find(repository);

		assertThat(row.rawAccountStatus()).isEqualTo("ACTIVE");
		assertThat(row.leftAt()).isNull();
		assertThat(row.inactivatedReasonCode()).isNull();
		assertThat(row.inactivatedById()).isNull();
		assertThat(row.inactivatedByName()).isNull();
		assertThat(cohortMemberStatus(repository)).isEqualTo("ACTIVE");
	}

	private void suspend(JdbcTraineeRosterRepository repository) {
		repository.updateStatus(TRAINEE, "INACTIVE", ACTOR, "ADMIN_SUSPENDED", "중도 이탈 처리");
		repository.updateCohortMembership(TRAINEE, COHORT_7, ORG_ID, true);
	}

	private void reactivate(JdbcTraineeRosterRepository repository) {
		repository.updateStatus(TRAINEE, "ACTIVE", ACTOR, null, null);
		repository.updateCohortMembership(TRAINEE, COHORT_7, ORG_ID, false);
	}

	private TraineeRosterRepository.RosterRow find(JdbcTraineeRosterRepository repository) {
		return repository.findTrainee(TRAINEE, COHORT_7, ORG_ID)
				.orElseThrow(() -> new AssertionError("교육생 행이 없습니다."));
	}

	private String cohortMemberStatus(JdbcTraineeRosterRepository repository) {
		return jdbcTemplate().queryForObject(
				"SELECT status FROM cohort_member WHERE user_id = ? AND cohort_id = ?",
				String.class, TRAINEE, COHORT_7);
	}

	private JdbcTemplate jdbcTemplate() {
		return new JdbcTemplate(dataSource());
	}

	private DriverManagerDataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, "postgres", "devcheck");
		dataSource.setDriverClassName("org.postgresql.Driver");
		return dataSource;
	}

	private JdbcTraineeRosterRepository repositoryOrSkip() {
		boolean ready;
		try (var connection = dataSource().getConnection();
			 var statement = connection.createStatement();
			 var resultSet = statement.executeQuery(
					 "SELECT COUNT(*) FROM cohort_member WHERE user_id = '" + TRAINEE + "'")) {
			ready = resultSet.next() && resultSet.getInt(1) > 0;
		} catch (Exception exception) {
			ready = false;
		}
		assumeTrue(ready, "검증용 픽스처가 없어 건너뜁니다.");
		return new JdbcTraineeRosterRepository(jdbcTemplate());
	}
}
