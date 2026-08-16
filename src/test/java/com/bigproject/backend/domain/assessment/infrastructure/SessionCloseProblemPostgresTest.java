package com.bigproject.backend.domain.assessment.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 문제 종료 확정을 <b>실제 PostgreSQL에서</b> 확인한다.
 *
 * <p>목 테스트로는 잡히지 않는 종류다. {@link JdbcSessionRepository#closeProblem}이 옮기는
 * 상태 전이는 전부 {@code ck_problem_stage_status_2}가 판정하는데, 목은 그 CHECK를 모른다 —
 * 종전 구현이 답변이 들어간 축을 건드리지 못한 이유도 "그렇게 짰기 때문"이 아니라
 * <b>그 상태를 표현할 값이 DDL에 없었기 때문</b>이었다.
 *
 * <p>픽스처의 L1은 질문에만 답하고 미달인 채 열려 있는 축이다(힌트 둘 NULL). 2026-08-17
 * CHECK 완화 전에는 이 행이 어떤 종료 상태도 될 수 없어 {@code IN_PROGRESS}로 남았고,
 * 그래서 리포트 대상 선별이 그 문제를 영영 집지 못했다.
 */
@Testcontainers(disabledWithoutDocker = true)
class SessionCloseProblemPostgresTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");
	private static final Path FIXTURE = Path.of("src/test/resources/fixtures/session-close-problem-fixture.sql");

	private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000161");
	private static final UUID PROBLEM_1 = UUID.fromString("00000000-0000-0000-0000-00000000017a");
	private static final UUID PROBLEM_2 = UUID.fromString("00000000-0000-0000-0000-00000000017b");

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	private static JdbcTemplate jdbc;
	private static JdbcSessionRepository repository;

	@BeforeAll
	static void loadSchema() throws IOException, InterruptedException {
		assumeTrue(Files.exists(DDL), "정본 DDL 문서가 없어 PostgreSQL 검증을 건너뜁니다.");

		POSTGRES.start();
		copyAndRun(DDL, "/tmp/schema.sql");

		jdbc = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
		repository = new JdbcSessionRepository(jdbc);
	}

	@BeforeEach
	void reloadFixture() throws IOException, InterruptedException {
		copyAndRun(FIXTURE, "/tmp/fixture.sql");
	}

	@Test
	@DisplayName("문제를 닫으면 열린 축이 답변 유무에 따라 NOT_PASSED·NOT_REACHED로 갈린다")
	void closesOpenStagesByWhetherTheyWereAnswered() {
		int closed = repository.closeProblem(SESSION, PROBLEM_1, JdbcSessionRepository.CLOSE_CURSOR_MOVED);

		assertThat(closed).as("L1(답변 있음) + L2·L4(미착수)").isEqualTo(3);
		assertThat(stagesOf(PROBLEM_1))
				.extracting(row -> row.get("axis_code"), row -> row.get("status"))
				.containsExactly(
						// 답이 들어간 채 열려 있던 축. 완화 이전에는 여기가 IN_PROGRESS 로 남았다
						tuple("L1", "NOT_PASSED"),
						tuple("L2", "NOT_REACHED"),
						// 이미 끝난 축은 건드리지 않는다
						tuple("L3", "PASSED"),
						tuple("L4", "NOT_REACHED"));
	}

	@Test
	@DisplayName("종료 표식은 그 문제의 전 축에 같은 값으로 찍힌다")
	void stampsEveryStageOfTheProblem() {
		repository.closeProblem(SESSION, PROBLEM_1, JdbcSessionRepository.CLOSE_CURSOR_MOVED);

		assertThat(stagesOf(PROBLEM_1))
				.allSatisfy(row -> {
					assertThat(row.get("problem_closed_at")).isNotNull();
					assertThat(row.get("problem_close_reason_code")).isEqualTo("CURSOR_MOVED");
				})
				.extracting(row -> row.get("problem_closed_at"))
				.as("한 UPDATE 로 찍으므로 축마다 시각이 갈리지 않는다")
				.containsOnly(stagesOf(PROBLEM_1).get(0).get("problem_closed_at"));
	}

	@Test
	@DisplayName("다른 문제는 건드리지 않는다 — 팀 공유 문제라도 종료는 문제 단위다")
	void leavesOtherProblemsOpen() {
		repository.closeProblem(SESSION, PROBLEM_1, JdbcSessionRepository.CLOSE_CURSOR_MOVED);

		assertThat(stagesOf(PROBLEM_2))
				.extracting(row -> row.get("problem_closed_at"))
				.containsOnlyNulls();
	}

	@Test
	@DisplayName("두 번 불러도 첫 종료 시각이 유지된다 — 호출부가 중복을 걱정하지 않아도 된다")
	void isIdempotent() {
		repository.closeProblem(SESSION, PROBLEM_1, JdbcSessionRepository.CLOSE_CURSOR_MOVED);
		Instant first = closedAt(PROBLEM_1);

		int closedAgain = repository.closeProblem(SESSION, PROBLEM_1,
				JdbcSessionRepository.CLOSE_HINTS_EXHAUSTED);

		assertThat(closedAgain).as("이미 다 닫혀 옮길 축이 없다").isZero();
		assertThat(closedAt(PROBLEM_1)).isEqualTo(first);
		assertThat(reasonOf(PROBLEM_1)).as("사유도 첫 값이 남는다").isEqualTo("CURSOR_MOVED");
	}

	@Test
	@DisplayName("세션을 닫으면 남은 문제가 한꺼번에 종료된다")
	void endClosesEveryRemainingProblem() {
		repository.end(SESSION, "ALL_PROBLEMS_TERMINAL", "L4");

		assertThat(stagesOf(PROBLEM_2))
				.extracting(row -> row.get("axis_code"), row -> row.get("status"),
						row -> row.get("problem_close_reason_code"))
				.containsExactly(
						// 답이 들어간 축은 NOT_ANSWERED 가 될 수 없다(아홉 슬롯 전부 NULL 요구)
						tuple("L1", "NOT_PASSED", "SESSION_ENDED"),
						tuple("L2", "NOT_ANSWERED", "SESSION_ENDED"));
	}

	@Test
	@DisplayName("세션 종료가 이미 닫힌 문제의 사유를 덮어쓰지 않는다")
	void endKeepsEarlierCloseReason() {
		repository.closeProblem(SESSION, PROBLEM_1, JdbcSessionRepository.CLOSE_HINTS_EXHAUSTED);

		repository.end(SESSION, "ALL_PROBLEMS_TERMINAL", "L4");

		assertThat(reasonOf(PROBLEM_1)).isEqualTo("HINTS_EXHAUSTED");
		assertThat(reasonOf(PROBLEM_2)).isEqualTo("SESSION_ENDED");
	}

	/**
	 * 리포트 대상 선별이 실제로 이 세션을 집는지까지 본다. 상태만 맞고 표식이 없으면
	 * (혹은 그 반대면) 리포트는 여전히 만들어지지 않는다 — 두 조건은 함께 만족해야 한다.
	 */
	@Test
	@DisplayName("세션 종료 후에는 리포트 대상 조건을 두 문제 모두 만족한다")
	void satisfiesReportDispatchPredicate() {
		repository.end(SESSION, "ALL_PROBLEMS_TERMINAL", "L4");

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM problem_stage
				 WHERE session_id = ? AND status IN ('PREPARED', 'IN_PROGRESS')
				""", Integer.class, SESSION))
				.as("음의 조건 — 열린 축이 없다").isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT problem_id) FROM problem_stage
				 WHERE session_id = ? AND problem_closed_at IS NOT NULL
				""", Integer.class, SESSION))
				.as("양의 조건 — 두 문제 모두 종료 표식이 있다").isEqualTo(2);
	}

	private static List<Map<String, Object>> stagesOf(UUID problemId) {
		return jdbc.queryForList("""
				SELECT axis_code, status, problem_closed_at, problem_close_reason_code
				  FROM problem_stage WHERE problem_id = ? ORDER BY question_sequence_no
				""", problemId);
	}

	private static Instant closedAt(UUID problemId) {
		return jdbc.queryForObject("""
				SELECT MIN(problem_closed_at) FROM problem_stage WHERE problem_id = ?
				""", Instant.class, problemId);
	}

	private static String reasonOf(UUID problemId) {
		return jdbc.queryForObject("""
				SELECT MIN(problem_close_reason_code) FROM problem_stage WHERE problem_id = ?
				""", String.class, problemId);
	}

	private static void copyAndRun(Path script, String target) throws IOException, InterruptedException {
		POSTGRES.copyFileToContainer(MountableFile.forHostPath(script.toAbsolutePath()), target);
		Container.ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(),
				"-d", POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", target);
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
	}
}
