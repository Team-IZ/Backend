package com.bigproject.backend.domain.manager.infrastructure;

import com.bigproject.backend.domain.manager.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 실제 PostgreSQL에서 명부 조회 SQL을 검증한다.
 *
 * <p>이 리포지토리는 {@code = ANY(uuid[])}, {@code COUNT(*) FILTER}, {@code ROW_NUMBER() OVER},
 * {@code ILIKE ... ESCAPE}, 생성 컬럼처럼 PostgreSQL 전용 문법에 의존하므로 호환 모드 인메모리 DB로 대체하지 않는다.
 * Docker를 쓸 수 없는 환경에서는 테스트가 실행되지 않고 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcTraineeRosterRepositoryTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

	private JdbcTemplate jdbcTemplate;
	private JdbcTraineeRosterRepository repository;

	private UUID organizationId;
	private UUID cohortId;
	private UUID classAId;
	private UUID classBId;
	private UUID managerId;

	private UUID project1Id;
	private UUID project2Id;
	private UUID round1Id;
	private UUID round2Id;
	private UUID conceptSet1Id;
	private UUID conceptSet2Id;

	private UUID traineeAId;
	private UUID traineeBId;
	private UUID traineeCId;
	private UUID traineeDId;
	private UUID traineeEId;
	private UUID traineeWildcardId;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
		);
		dataSource.setDriverClassName(POSTGRES.getDriverClassName());
		jdbcTemplate = new JdbcTemplate(dataSource);
		repository = new JdbcTraineeRosterRepository(jdbcTemplate);
		resetSchema(dataSource);
		insertFixture();
	}

	@Test
	void pagesRosterWithinAccessibleClassOnlyAndAppliesAccountStatusFilter() {
		var all = repository.findRosterPage(criteria(null, null), round2Id, 0, 20);

		assertThat(all.totalElements()).isEqualTo(5);
		assertThat(all.content()).extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.doesNotContain(traineeEId);

		assertThat(repository.findRosterPage(criteria(AccountStatus.ACTIVE, null), round2Id, 0, 20).content())
				.extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.containsExactlyInAnyOrder(traineeAId, traineeBId, traineeWildcardId);
		assertThat(repository.findRosterPage(criteria(AccountStatus.INVITED, null), round2Id, 0, 20).content())
				.extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.containsExactly(traineeCId);
		assertThat(repository.findRosterPage(criteria(AccountStatus.INACTIVE, null), round2Id, 0, 20).content())
				.extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.containsExactly(traineeDId);
	}

	@Test
	void keepsTotalCountWhenRequestedPageIsOutOfRange() {
		var outOfRange = repository.findRosterPage(criteria(null, null), round2Id, 9, 20);

		// 페이지 범위 초과는 행이 0건이어도 검색 결과 없음과 구분되어야 하므로 총 건수는 유지한다.
		assertThat(outOfRange.content()).isEmpty();
		assertThat(outOfRange.totalElements()).isEqualTo(5);
	}

	@Test
	void reportsNoAttemptWhenTraineeHasNoInitialAttemptForRound() {
		var content = repository.findRosterPage(criteria(null, null), round2Id, 0, 20).content();

		assertThat(content).filteredOn(row -> row.userId().equals(traineeCId)).singleElement()
				.satisfies(row -> assertThat(row.attemptId()).isNull());
		assertThat(content).filteredOn(row -> row.userId().equals(traineeAId)).singleElement()
				.satisfies(row -> {
					assertThat(row.attemptId()).isNotNull();
					assertThat(row.attemptStatus()).isEqualTo("COMPLETED");
				});
	}

	@Test
	void matchesNameSearchCaseInsensitivelyAndEscapesLikeWildcards() {
		assertThat(repository.findRosterPage(criteria(null, "하늘"), round2Id, 0, 20).content())
				.extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.containsExactly(traineeAId);

		// '%'는 와일드카드가 아니라 문자 그대로 검색되어야 한다.
		assertThat(repository.findRosterPage(criteria(null, "100%"), round2Id, 0, 20).content())
				.extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.containsExactly(traineeWildcardId);
		assertThat(repository.findRosterPage(criteria(null, "%"), round2Id, 0, 20).content())
				.extracting(TraineeRosterRepository.RosterMemberRow::userId)
				.containsExactly(traineeWildcardId);
	}

	@Test
	void computesConceptReachFromPassedProblemStagesOnly() {
		var results = repository.findConceptResults(List.of(traineeAId, traineeBId), conceptSet2Id, round2Id);

		// traineeA: 3개 개념 모두 GENERATED + L4 통과
		assertThat(results).filteredOn(r -> r.userId().equals(traineeAId))
				.extracting(TraineeRosterRepository.ConceptResultRow::sequenceNo,
						TraineeRosterRepository.ConceptResultRow::generationStatus,
						TraineeRosterRepository.ConceptResultRow::highestReachedLevel)
				.containsExactlyInAnyOrder(
						tuple(1, "GENERATED", 4),
						tuple(2, "GENERATED", 4),
						tuple(3, "GENERATED", 4)
				);

		// traineeB: 1번 개념은 L1만 통과(L2는 미통과라 도달 단계에 반영되지 않음), 2번 L4, 3번은 코드 근거 없어 미출제
		assertThat(results).filteredOn(r -> r.userId().equals(traineeBId))
				.extracting(TraineeRosterRepository.ConceptResultRow::sequenceNo,
						TraineeRosterRepository.ConceptResultRow::generationStatus,
						TraineeRosterRepository.ConceptResultRow::highestReachedLevel)
				.containsExactlyInAnyOrder(
						tuple(1, "GENERATED", 1),
						tuple(2, "GENERATED", 4),
						tuple(3, "NOT_GENERATED", null)
				);
	}

	@Test
	void countsExcellentOccurrenceAcrossRoundsUpToSelectedSequence() {
		var throughSecondRound = repository.findExcellentOccurrenceCounts(
				List.of(traineeAId, traineeBId), cohortId, 2
		);
		assertThat(throughSecondRound)
				.extracting(TraineeRosterRepository.ExcellentOccurrenceRow::userId,
						TraineeRosterRepository.ExcellentOccurrenceRow::occurrenceCount)
				.contains(tuple(traineeAId, 2));
		assertThat(throughSecondRound).filteredOn(row -> row.userId().equals(traineeBId))
				.allSatisfy(row -> assertThat(row.occurrenceCount()).isZero());

		// 1차까지만 보면 누적은 1회여야 한다. 선택 회차 이후의 우수는 포함하지 않는다.
		assertThat(repository.findExcellentOccurrenceCounts(List.of(traineeAId), cohortId, 1))
				.extracting(TraineeRosterRepository.ExcellentOccurrenceRow::occurrenceCount)
				.containsExactly(1);
	}

	@Test
	void countsAccountStatusesForHeaderWithinAccessibleClassesOnly() {
		var counts = repository.countAccountStatuses(cohortId, organizationId, List.of(classAId));

		assertThat(counts.totalCount()).isEqualTo(5);
		assertThat(counts.activeCount()).isEqualTo(3);
		assertThat(counts.invitationPendingCount()).isEqualTo(1);
		assertThat(counts.inactiveCount()).isEqualTo(1);
		assertThat(counts.activeCount() + counts.invitationPendingCount() + counts.inactiveCount())
				.isEqualTo(counts.totalCount());
	}

	private TraineeRosterRepository.Criteria criteria(AccountStatus accountStatus, String query) {
		return new TraineeRosterRepository.Criteria(
				cohortId, organizationId, List.of(classAId), accountStatus, query
		);
	}

	private void resetSchema(DataSource dataSource) {
		jdbcTemplate.execute("DROP SCHEMA public CASCADE");
		jdbcTemplate.execute("CREATE SCHEMA public");
		ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
				new ClassPathResource("db/postgresql/manager-trainee-roster-schema.sql")
		);
		populator.execute(dataSource);
	}

	private void insertFixture() {
		organizationId = UUID.randomUUID();
		cohortId = UUID.randomUUID();
		classAId = UUID.randomUUID();
		classBId = UUID.randomUUID();
		managerId = UUID.randomUUID();
		UUID managerRoleId = UUID.randomUUID();
		UUID traineeRoleId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-01T00:00:00Z");

		jdbcTemplate.update("INSERT INTO organization VALUES (?, '테스트 기관', 'ACTIVE', NULL)", organizationId);
		jdbcTemplate.update("INSERT INTO \"role\" VALUES (?, 'MANAGER')", managerRoleId);
		jdbcTemplate.update("INSERT INTO \"role\" VALUES (?, 'TRAINEE')", traineeRoleId);
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, ?, ?, 'manager@example.com', '매니저', 'ACTIVE', NULL)",
				managerId, organizationId, managerRoleId
		);
		jdbcTemplate.update("INSERT INTO cohort VALUES (?, ?, '7기', 'RUNNING', NULL)", cohortId, organizationId);
		jdbcTemplate.update("INSERT INTO \"class\" VALUES (?, ?, ?, 'A반', 'ACTIVE', NULL)", classAId, organizationId, cohortId);
		jdbcTemplate.update("INSERT INTO \"class\" VALUES (?, ?, ?, 'B반', 'ACTIVE', NULL)", classBId, organizationId, cohortId);
		jdbcTemplate.update(
				"INSERT INTO manager_assignment VALUES (?, ?, ?, ?, ?, NULL, 'ACTIVE')",
				UUID.randomUUID(), managerId, organizationId, classAId, Timestamp.from(now)
		);
		// B반은 과거에 담당했으나 이미 해제된 배정이라 현재 조회 범위에 들어오지 않는다.
		jdbcTemplate.update(
				"INSERT INTO manager_assignment VALUES (?, ?, ?, ?, ?, ?, 'ENDED')",
				UUID.randomUUID(), managerId, organizationId, classBId,
				Timestamp.from(now.minusSeconds(86400)), Timestamp.from(now)
		);

		project1Id = insertMiniProject(1, "미니프로젝트 1");
		project2Id = insertMiniProject(2, "미니프로젝트 2");
		conceptSet1Id = insertConceptSet(project1Id, "변수", "조건문", "반복문");
		conceptSet2Id = insertConceptSet(project2Id, "함수", "클래스", "예외처리");
		round1Id = insertRound(project1Id, conceptSet1Id, "1차");
		round2Id = insertRound(project2Id, conceptSet2Id, "2차");

		traineeAId = insertTrainee("정하늘", "hana@example.com", "ACTIVE", traineeRoleId, classAId);
		traineeBId = insertTrainee("김민준", "minjun@example.com", "ACTIVE", traineeRoleId, classAId);
		traineeCId = insertTrainee("최유나", "yuna@example.com", "PENDING", traineeRoleId, classAId);
		traineeDId = insertTrainee("오세림", "serim@example.com", "INACTIVE", traineeRoleId, classAId);
		traineeWildcardId = insertTrainee("박100%달성", "wild@example.com", "ACTIVE", traineeRoleId, classAId);
		traineeEId = insertTrainee("다른반", "other@example.com", "ACTIVE", traineeRoleId, classBId);

		// traineeA: 1·2차 모두 3개 개념 전부 L4 통과 -> 우수 2회
		insertAllMaxAttempt(traineeAId, project1Id, round1Id, conceptSet1Id);
		insertAllMaxAttempt(traineeAId, project2Id, round2Id, conceptSet2Id);

		// traineeB: 2차만 응시. 1번 개념은 L1 통과·L2 미통과, 2번 개념 L4 통과, 3번 개념은 미출제
		UUID attemptB = UUID.randomUUID();
		UUID codeAnalysisB = insertCodeAnalysis();
		insertAttempt(attemptB, project2Id, round2Id, traineeBId, codeAnalysisB);
		UUID sessionB = insertSession(attemptB);
		List<UUID> round2Concepts = conceptIds(conceptSet2Id);
		UUID problemB1 = insertProblem(codeAnalysisB, round2Concepts.get(0), 1, true);
		UUID problemB2 = insertProblem(codeAnalysisB, round2Concepts.get(1), 2, true);
		insertProblem(codeAnalysisB, round2Concepts.get(2), 3, false);
		insertStage(sessionB, problemB1, "L1", "PASSED");
		insertStage(sessionB, problemB1, "L2", "NOT_PASSED");
		insertStage(sessionB, problemB2, "L4", "PASSED");

		// traineeC·D·wildcard: 2차 응시 기록 없음(NO_ATTEMPT)
	}

	private UUID insertMiniProject(int sequenceNo, String name) {
		UUID projectId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO project VALUES (?, ?, ?, ?, ?, 'MINI_PROJECT', ?, ?, 'RUNNING', NULL)",
				projectId, organizationId, cohortId, name, sequenceNo,
				LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31)
		);
		return projectId;
	}

	private UUID insertConceptSet(UUID projectId, String... conceptNames) {
		UUID conceptSetId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO project_verification_concept_set VALUES (?, ?, ?, 1, 'ACTIVE', CURRENT_TIMESTAMP, NULL)",
				conceptSetId, projectId, organizationId
		);
		for (int index = 0; index < conceptNames.length; index++) {
			UUID teachesId = UUID.randomUUID();
			jdbcTemplate.update(
					"INSERT INTO teaches VALUES (?, ?, ?, ?, 'ACTIVE')",
					teachesId, organizationId, conceptNames[index], conceptNames[index]
			);
			jdbcTemplate.update(
					"INSERT INTO project_verification_concept VALUES (?, ?, ?, ?, ?)",
					UUID.randomUUID(), conceptSetId, organizationId, teachesId, index + 1
			);
		}
		return conceptSetId;
	}

	private UUID insertRound(UUID projectId, UUID conceptSetId, String roundName) {
		UUID roundId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO project_assessment_round VALUES (?, ?, ?, ?, ?, 1, ?, ?, TRUE, 'COMPLETED', NULL)",
				roundId, projectId, organizationId, cohortId, conceptSetId, roundName,
				Timestamp.from(Instant.parse("2026-07-15T00:00:00Z"))
		);
		return roundId;
	}

	private List<UUID> conceptIds(UUID conceptSetId) {
		return jdbcTemplate.query(
				"SELECT project_concept_id FROM project_verification_concept WHERE concept_set_id = ? ORDER BY sequence_no",
				(rs, rowNum) -> rs.getObject("project_concept_id", UUID.class),
				conceptSetId
		);
	}

	private UUID insertTrainee(String name, String email, String status, UUID roleId, UUID classId) {
		UUID userId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO app_user VALUES (?, ?, ?, ?, ?, ?, NULL)",
				userId, organizationId, roleId, email, name, status
		);
		UUID cohortMemberId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO cohort_member VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, NULL, 'ACTIVE')",
				cohortMemberId, organizationId, cohortId, userId
		);
		jdbcTemplate.update(
				"INSERT INTO class_membership VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, NULL)",
				UUID.randomUUID(), classId, cohortMemberId, organizationId
		);
		return userId;
	}

	private UUID insertCodeAnalysis() {
		UUID analysisId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO code_analysis VALUES (?, ?, 'ACTIVE')", analysisId, organizationId);
		return analysisId;
	}

	private void insertAttempt(UUID attemptId, UUID projectId, UUID roundId, UUID userId, UUID codeAnalysisId) {
		jdbcTemplate.update(
				"INSERT INTO measurement_attempt VALUES (?, ?, ?, ?, ?, ?, ?, 'INITIAL', 1, 'COMPLETED', 'NOT_REQUIRED')",
				attemptId, organizationId, cohortId, roundId, projectId, userId, codeAnalysisId
		);
	}

	private UUID insertSession(UUID attemptId) {
		UUID sessionId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO assessment_session VALUES (?, ?, ?, 'COMPLETED')",
				sessionId, organizationId, attemptId
		);
		return sessionId;
	}

	private UUID insertProblem(UUID codeAnalysisId, UUID conceptId, int problemNo, boolean generated) {
		UUID problemId = UUID.randomUUID();
		if (generated) {
			jdbcTemplate.update(
					"INSERT INTO assessment_problem VALUES (?, ?, ?, 'TEAM_SHARED_PROBLEM', ?, ?, '문제', 'GENERATED', NULL)",
					problemId, organizationId, codeAnalysisId, conceptId, problemNo
			);
		} else {
			jdbcTemplate.update(
					"INSERT INTO assessment_problem VALUES (?, ?, ?, 'TEAM_SHARED_PROBLEM', ?, ?, NULL, 'NOT_GENERATED',"
							+ " 'NO_MATCHING_CODE_EVIDENCE')",
					problemId, organizationId, codeAnalysisId, conceptId, problemNo
			);
		}
		return problemId;
	}

	private void insertStage(UUID sessionId, UUID problemId, String axisCode, String status) {
		jdbcTemplate.update(
				"INSERT INTO problem_stage (problem_stage_id, session_id, problem_id, axis_code, status)"
						+ " VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), sessionId, problemId, axisCode, status
		);
	}

	private void insertAllMaxAttempt(UUID userId, UUID projectId, UUID roundId, UUID conceptSetId) {
		UUID attemptId = UUID.randomUUID();
		UUID codeAnalysisId = insertCodeAnalysis();
		insertAttempt(attemptId, projectId, roundId, userId, codeAnalysisId);
		UUID sessionId = insertSession(attemptId);
		int problemNo = 1;
		for (UUID conceptId : conceptIds(conceptSetId)) {
			UUID problemId = insertProblem(codeAnalysisId, conceptId, problemNo++, true);
			insertStage(sessionId, problemId, "L4", "PASSED");
		}
	}
}
