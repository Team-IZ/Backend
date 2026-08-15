package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code GET /assessment-sessions/current}가 <b>마감이 지난 세션을 내주지 않는지</b>를
 * 진짜 PostgreSQL에서 본다. {@link JdbcSessionRepository#findCurrent}의 실제 SQL을 그대로 태운다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>종전에는 살아 있는 상태({@code READY}·{@code IN_PROGRESS}·{@code PAUSED})만 보고 창을 읽지
 * 않았다. 그래서 개인 응시 창이 닫힌 뒤에도 화면이 "지금 이어서 할 세션"을 그려 놓고
 * {@code POST /start}에서만 409가 났다 — 학생은 들어갈 수 없는 시험을 계속 권유받는다.
 *
 * <p>네이티브 SQL이라 컴파일도 목 기반 단위 테스트도 이 조건을 검증하지 못한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcSessionCurrentWindowPostgresTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	/** 개인 창이 열려 있는 1차 응시. */
	private static final UUID OPEN_USER = UUID.fromString("22222222-2222-4222-8222-000000000001");
	/** 개인 창이 닫힌 1차 응시. */
	private static final UUID CLOSED_USER = UUID.fromString("22222222-2222-4222-8222-000000000002");
	/** 마감이 지난 다시 보기. */
	private static final UUID REVIEW_EXPIRED_USER = UUID.fromString("22222222-2222-4222-8222-000000000003");
	/** 창이 아직 정해지지 않은 응시(분석 직후). */
	private static final UUID NO_WINDOW_USER = UUID.fromString("22222222-2222-4222-8222-000000000004");

	private static JdbcSessionRepository repository;

	@BeforeAll
	static void loadSchema() throws IOException, InterruptedException {
		assumeTrue(Files.exists(DDL), "정본 DDL 문서가 없어 PostgreSQL 검증을 건너뜁니다.");

		POSTGRES.start();
		POSTGRES.copyFileToContainer(MountableFile.forHostPath(DDL.toAbsolutePath()), "/tmp/schema.sql");
		Container.ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(),
				"-d", POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", "/tmp/schema.sql");
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();

		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
		seed(jdbc);
		repository = new JdbcSessionRepository(jdbc);
	}

	/**
	 * 네 사람에게 {@code READY} 세션을 하나씩 준다. 다른 것은 같고 <b>마감만</b> 다르다.
	 *
	 * <p>FK는 이 검증 대상이 아니라 트리거를 끄고 넣는다. CHECK 제약은 그대로 걸리므로
	 * REVIEW 응시에는 {@code ck_measurement_attempt_attempt_type_2}가 요구하는 6개를 다 채운다.
	 */
	private static void seed(JdbcTemplate jdbc) {
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = replica");
				attempt(statement, OPEN_USER, "INITIAL",
						"now() - make_interval(hours => 1)", "now() + make_interval(hours => 5)", null);
				attempt(statement, CLOSED_USER, "INITIAL",
						"now() - make_interval(days => 3)", "now() - make_interval(days => 2)", null);
				attempt(statement, REVIEW_EXPIRED_USER, "REVIEW",
						"now() - make_interval(days => 3)", "now() + make_interval(days => 1)",
						"now() - make_interval(hours => 2)");
				attempt(statement, NO_WINDOW_USER, "INITIAL", "NULL", "NULL", null);
			}
			return null;
		});
	}

	private static void attempt(java.sql.Statement statement, UUID userId, String attemptType,
			String openAt, String closeAt, String reviewDueAt) throws java.sql.SQLException {

		boolean review = "REVIEW".equals(attemptType);
		String attemptId = UUID.randomUUID().toString();
		statement.execute("""
				INSERT INTO measurement_attempt (
				    attempt_id, org_id, cohort_id, assessment_round_id, project_id, user_id,
				    attempt_type, source_attempt_id, attempt_sequence_no, assigned_at, assigned_by,
				    review_source_report_id, review_source_report_snapshot_id, review_due_at,
				    status, validity_review_status, analysis_completed_at,
				    assessment_open_at, assessment_close_at)
				VALUES ('%s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
				    gen_random_uuid(), '%s', '%s', %s, 1, %s, %s, %s, %s, %s,
				    'SESSION_READY', 'NOT_REQUIRED', now() - make_interval(days => 1), %s, %s)
				""".formatted(attemptId, userId, attemptType,
				review ? "gen_random_uuid()" : "NULL",
				review ? "now()" : "NULL",
				review ? "gen_random_uuid()" : "NULL",
				review ? "gen_random_uuid()" : "NULL",
				review ? "gen_random_uuid()" : "NULL",
				reviewDueAt == null ? "NULL" : reviewDueAt,
				openAt, closeAt));

		statement.execute("""
				INSERT INTO assessment_session (
				    session_id, org_id, attempt_id, status, window_leave_count, total_away_seconds,
				    connection_loss_count, total_disconnected_seconds, row_version, updated_at)
				VALUES (gen_random_uuid(), gen_random_uuid(), '%s', 'READY', 0, 0, 0, 0, 0, now())
				""".formatted(attemptId));
	}

	@Test
	@DisplayName("개인 창이 열려 있으면 이어서 할 세션으로 나온다")
	void anOpenWindowStillOffersTheSession() {
		assertThat(repository.findCurrent(OPEN_USER)).isPresent();
	}

	@Test
	@DisplayName("개인 창이 닫히면 이어서 할 세션이 없다 — 204")
	void aClosedWindowIsNotOffered() {
		assertThat(repository.findCurrent(CLOSED_USER))
				.as("창이 지난 READY 세션을 내주면 화면이 시작할 수 없는 시험을 권한다")
				.isEmpty();
	}

	@Test
	@DisplayName("다시 보기도 마감이 지나면 나오지 않는다")
	void anExpiredReviewIsNotOffered() {
		assertThat(repository.findCurrent(REVIEW_EXPIRED_USER)).isEmpty();
	}

	/** 마감 컬럼이 비어 있으면 막지 않는다 — 없는 규칙을 만들지 않는다. {@code SessionGuard}와 같다. */
	@Test
	@DisplayName("창이 아직 정해지지 않았으면 막지 않는다")
	void aMissingWindowDoesNotHide() {
		Optional<SessionHead> found = repository.findCurrent(NO_WINDOW_USER);

		assertThat(found).isPresent();
		assertThat(found.get().assessmentCloseAt()).isNull();
	}
}
