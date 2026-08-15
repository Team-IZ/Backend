package com.bigproject.backend.domain.reporting.infrastructure;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 리포트 발행 대상 판정이 <b>회차 창이 비어 있어도</b> 성립하는지 진짜 PostgreSQL에서 본다.
 *
 * <h2>왜 이 시험이 필요한가</h2>
 *
 * <p>{@code project_assessment_round.assessment_due_at}(회차 응시 창)은 2026-08-16에 폐기돼
 * 전 행 {@code NULL}이다. {@link ReportDispatchRepository}가 종전처럼
 * {@code r.assessment_due_at IS NOT NULL}로 대상을 고르면 <b>그 조건이 전부 거짓이 되어 발행
 * 대상이 0건</b>이 된다 — 스케줄러는 계속 도는데 아무것도 발행하지 않는다.
 *
 * <p>🔴 <b>회귀 방향이 "조용히 0건"이라는 것이 이 시험의 존재 이유다.</b> 예외도 로그도 나지
 * 않고 리포트만 끊긴다. 네이티브 SQL이라 컴파일도 단위 테스트도 잡지 못한다.
 *
 * <p>레포지토리의 SQL 문자열을 그대로 실행할 수는 없어서(상수 조립·JPA 바인딩) 판정의
 * <b>핵심 술어</b>를 같은 모양으로 옮겨 본다. 술어가 갈라지면 아래 소스 검사가 먼저 걸린다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReportDispatchWindowPostgresTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");

	private static final Path REPOSITORY = Path.of("src/main/java/com/bigproject/backend/domain"
			+ "/reporting/infrastructure/ReportDispatchRepository.java");

	/** 발행 대상 판정의 핵심. 레포지토리의 네 조회가 전부 이 모양을 쓴다. */
	private static final String DUE_PREDICATE = """
			SELECT count(*)
			  FROM assessment_session s
			  JOIN measurement_attempt ma ON ma.attempt_id = s.attempt_id
			  JOIN project_assessment_round r
			    ON r.assessment_round_id = ma.assessment_round_id
			   AND r.deleted_at IS NULL
			 WHERE s.status  = 'COMPLETED'
			   AND ma.status = 'COMPLETED'
			   AND s.ended_at IS NOT NULL
			   AND COALESCE(ma.assessment_close_at, r.assessment_due_at) IS NOT NULL
			   AND s.ended_at < COALESCE(ma.assessment_close_at, r.assessment_due_at)
			   AND now() >= COALESCE(r.report_publish_not_before_at,
			                         ma.assessment_close_at, r.assessment_due_at)
			   AND ma.validity_review_status <> 'CONFIRMED_INVALID'
			""";

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	private static JdbcTemplate jdbc;

	@BeforeAll
	static void loadSchema() throws IOException, InterruptedException {
		assumeTrue(Files.exists(DDL), "정본 DDL 문서가 없어 PostgreSQL 검증을 건너뜁니다.");

		POSTGRES.start();
		POSTGRES.copyFileToContainer(MountableFile.forHostPath(DDL.toAbsolutePath()), "/tmp/schema.sql");
		Container.ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(),
				"-d", POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", "/tmp/schema.sql");
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();

		jdbc = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

		seedCompletedSessionWithoutRoundWindow();
	}

	/**
	 * 회차 창은 비우고 <b>개인 창만</b> 채운 정상 완료 세션 하나. 폐기 이후의 운영 데이터 모양이다.
	 *
	 * <p>FK는 이 검증 대상이 아니라 트리거를 끄고 넣는다. 그 설정은 세션 단위라 INSERT와 같은
	 * 커넥션에서 해야 한다. <b>CHECK 제약은 그대로 걸리므로</b> 종료 3종·발행 시각 순서를 지켜야 한다.
	 */
	private static void seedCompletedSessionWithoutRoundWindow() {
		jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = replica");
				statement.execute("""
						INSERT INTO project_assessment_round (
						    assessment_round_id, project_id, org_id, cohort_id, round_no, round_name,
						    trigger_type, submission_due_at, assessment_open_at, assessment_due_at,
						    report_publish_mode, report_publish_not_before_at, is_final, status,
						    created_by, updated_by)
						VALUES ('11111111-1111-4111-8111-000000000001', gen_random_uuid(), gen_random_uuid(),
						    gen_random_uuid(), 1, '리포트 발행 판정 검증', 'SCHEDULED',
						    now() - make_interval(days => 10),
						    NULL, NULL,                                  -- 🔴 폐기된 회차 창
						    'ROUND_BATCH', now() - make_interval(days => 1), true, 'OPEN',
						    gen_random_uuid(), gen_random_uuid())
						""");
				statement.execute("""
						INSERT INTO measurement_attempt (
						    attempt_id, org_id, cohort_id, assessment_round_id, project_id, user_id,
						    attempt_type, attempt_sequence_no, status, terminal_reason_code, terminal_at,
						    validity_review_status, analysis_completed_at,
						    assessment_open_at, assessment_close_at)
						VALUES ('11111111-1111-4111-8111-000000000002', gen_random_uuid(), gen_random_uuid(),
						    '11111111-1111-4111-8111-000000000001', gen_random_uuid(), gen_random_uuid(),
						    'INITIAL', 1, 'COMPLETED', 'COMPLETED', now() - make_interval(days => 2),
						    'NOT_REQUIRED', now() - make_interval(days => 4),
						    now() - make_interval(days => 4), now() - make_interval(days => 3))
						""");
				statement.execute("""
						INSERT INTO assessment_session (
						    session_id, org_id, attempt_id, status, end_reason_code,
						    started_at, ended_at, window_leave_count, total_away_seconds,
						    connection_loss_count, total_disconnected_seconds, row_version, updated_at)
						VALUES (gen_random_uuid(), gen_random_uuid(),
						    '11111111-1111-4111-8111-000000000002', 'COMPLETED', 'ALL_PROBLEMS_TERMINAL',
						    now() - make_interval(days => 4), now() - make_interval(days => 4)
						        + make_interval(mins => 30),
						    0, 0, 0, 0, 0, now())
						""");
			}
			return null;
		});
	}

	@Test
	@DisplayName("회차 창이 비어도 개인 창으로 발행 대상이 잡힌다")
	void thePersonalWindowKeepsTheDispatchAlive() {
		Integer due = jdbc.queryForObject(DUE_PREDICATE, Integer.class);

		assertThat(due)
				.as("회차 창이 폐기됐어도 개인 창(measurement_attempt.assessment_close_at)으로 잡혀야 한다")
				.isEqualTo(1);
	}

	/**
	 * 종전 술어가 왜 못 쓰는지를 같은 데이터로 보여 둔다. 이 값이 1이 되면 회차 창이 되살아난
	 * 것이므로, 그때는 이 시험이 아니라 폐기 결정을 다시 봐야 한다.
	 */
	@Test
	@DisplayName("회차 창을 기준으로 삼으면 대상이 0건이 된다 — 조용히 끊기는 모양")
	void theRoundWindowSilentlyYieldsNothing() {
		Integer due = jdbc.queryForObject("""
				SELECT count(*)
				  FROM assessment_session s
				  JOIN measurement_attempt ma ON ma.attempt_id = s.attempt_id
				  JOIN project_assessment_round r
				    ON r.assessment_round_id = ma.assessment_round_id
				 WHERE s.status = 'COMPLETED' AND ma.status = 'COMPLETED'
				   AND r.assessment_due_at IS NOT NULL
				   AND s.ended_at < r.assessment_due_at
				""", Integer.class);

		assertThat(due).isZero();
	}

	/**
	 * 술어가 다시 회차 창으로 돌아가지 않게 소스를 직접 고정한다.
	 *
	 * <p>위 두 시험은 <b>옮겨 적은</b> 술어를 보므로, 레포지토리만 되돌아가면 통과해 버린다.
	 * 실제로 끊기는 원인이 되는 문자열 하나를 여기서 막는다.
	 */
	@Test
	@DisplayName("레포지토리가 회차 창 NOT NULL 을 다시 조건으로 쓰지 않는다")
	void theRepositoryNoLongerKeysOffTheRoundWindow() throws IOException {
		assumeTrue(Files.exists(REPOSITORY), "레포지토리 소스가 없어 건너뜁니다.");

		assertThat(Files.readString(REPOSITORY))
				.as("이 조건이 돌아오면 발행 대상이 예외 없이 0건이 된다 — 리포트가 조용히 끊긴다")
				.doesNotContain("AND r.assessment_due_at IS NOT NULL");
	}
}
