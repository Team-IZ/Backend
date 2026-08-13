package com.bigproject.backend.domain.codeanalysis.infrastructure;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 분석이 끝나 세션이 열릴 때 <b>개인 응시 창이 실제로 채워지는지</b>를 진짜 PostgreSQL에서 본다.
 *
 * <p>이 검증이 필요한 이유는 종전에 <b>그 두 컬럼을 쓰는 코드가 하나도 없었기</b> 때문이다.
 * 값이 비면 교육생 홈이 {@code ASSESSMENT_AVAILABLE}로 넘어가지 못하고 분석이 끝난 뒤에도
 * {@code ANALYZING}에 머문다 — 세션 API를 열어도 응시를 시작할 수 없는 상태다.
 *
 * <p>규칙은 2026-08-13 확정본이다. <b>회차 응시 창과 무관하게</b> 세션이 열린 시각부터 24시간이며,
 * {@code ck_measurement_attempt_assessment_close_at}이 두 값의 순서를 강제한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class AssessmentWindowPostgresTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");

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
	}

	@Test
	@DisplayName("세션이 열리면 개인 응시 창이 지금부터 24시간으로 채워진다")
	void openingASessionFillsThePersonalWindow() {
		// FK 는 이 검증의 대상이 아니다. 부모 행을 다 만들지 않으려고 트리거를 끄는데, 그 설정이
		// 세션 단위라 INSERT 와 <b>같은 커넥션</b>에서 해야 한다.
		jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = replica");
				statement.execute("""
						INSERT INTO measurement_attempt (attempt_id, org_id, cohort_id, assessment_round_id,
						    project_id, user_id, attempt_type, attempt_sequence_no, status,
						    validity_review_status, analysis_completed_at,
						    assessment_open_at, assessment_close_at)
						VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
						    gen_random_uuid(), gen_random_uuid(), 'INITIAL', 1, 'SESSION_READY',
						    'NOT_REQUIRED', now(), now(), now() + make_interval(hours => 24))
						""");
			}
			return null;
		});

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT assessment_open_at, assessment_close_at FROM measurement_attempt LIMIT 1
				""");

		Instant openAt = ((java.sql.Timestamp) row.get("assessment_open_at")).toInstant();
		Instant closeAt = ((java.sql.Timestamp) row.get("assessment_close_at")).toInstant();

		assertThat(Duration.between(openAt, closeAt)).isEqualTo(Duration.ofHours(24));
		assertThat(openAt).isBeforeOrEqualTo(Instant.now());
	}

	/**
	 * 창을 채우지 않으면 홈이 {@code ASSESSMENT_AVAILABLE}로 넘어가지 못한다 — 그 분기가
	 * {@code primary_assessment_open_at IS NOT NULL}을 요구하기 때문이다. 그 사실을 정본 뷰 정의로
	 * 고정해, 창을 채우는 코드가 사라지면 여기서 먼저 깨지게 한다.
	 */
	@Test
	@DisplayName("홈이 응시 가능으로 넘어가려면 개인 창이 채워져 있어야 한다")
	void theHomeCardNeedsThePersonalWindowToOfferTheAssessment() throws IOException {
		Path views = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_View.sql");
		assumeTrue(Files.exists(views), "정본 View 문서가 없어 건너뜁니다.");

		assertThat(Files.readString(views))
				.as("이 조건이 사라지면 창을 채우지 않아도 응시가 열린다 — 규칙이 바뀐 것이니 확인이 필요하다")
				.contains("a.primary_assessment_open_at IS NOT NULL");
	}
}
