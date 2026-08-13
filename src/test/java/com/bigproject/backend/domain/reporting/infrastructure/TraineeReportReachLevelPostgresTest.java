package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 개념 카드의 도달 단계({@code level})를 <b>실제 PostgreSQL에서</b> 확인한다.
 *
 * <p>계약 테스트({@link TraineeReportCanonicalSqlContractTest})는 컬럼이 있는지까지만 본다.
 * 이 결함은 컬럼이 멀쩡히 있는데 <b>값이 항상 같은</b> 종류라, 조인 결과의 의미론을 실제로
 * 돌려 봐야만 잡힌다 — 실서버에서 전 개념이 0단으로 나간 뒤에야 프론트가 발견했다(20차 R2).
 *
 * <p>픽스처는 미니프로젝트를 그대로 재현한다. 문제 3개가 모두 팀 공유 문제라
 * {@code assessment_problem.best_success_stage}는 CHECK가 NULL로 강제하고, 그래서 뷰의
 * {@code reach_display_code}는 셋 다 {@code L0}다. 실제 도달 단계는 3·2·0이다.
 */
@Testcontainers(disabledWithoutDocker = true)
class TraineeReportReachLevelPostgresTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");
	private static final Path VIEWS = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_View.sql");
	private static final Path FIXTURE = Path.of("src/test/resources/fixtures/trainee-report-reach-level-fixture.sql");

	/** 픽스처의 교육생. */
	private static final UUID TRAINEE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

	private static JdbcTemplate jdbc;

	@BeforeAll
	static void loadSchemaAndFixture() throws IOException, InterruptedException {
		assumeTrue(Files.exists(DDL) && Files.exists(VIEWS),
				"정본 DDL/View 문서가 없어 PostgreSQL 검증을 건너뜁니다.");

		POSTGRES.start();
		copyAndRun(DDL, "/tmp/schema.sql");
		copyAndRun(VIEWS, "/tmp/views.sql");
		copyAndRun(FIXTURE, "/tmp/fixture.sql");

		jdbc = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	@Test
	@DisplayName("도달 단계는 evidence의 reachedLevel을 쓰고, 없으면 problem_stage에서 다시 센다")
	void reachLevelComesFromEvidenceThenProblemStage() {
		List<ConceptRow> concepts = new JdbcTraineeReportQueryRepository(jdbc).findConcepts(TRAINEE);

		assertThat(concepts)
				.extracting(ConceptRow::conceptDisplayName, ConceptRow::reachLevel)
				.containsExactly(
						// trace_payload.reachedLevel = 3 (발행 시점에 얼린 값이 우선한다)
						tuple("예외 처리와 롤백 전략", 3),
						// reachedLevel 키가 없다 → problem_stage 의 통과 축 최댓값 L2
						tuple("API 응답 계약 설계", 2),
						// 통과한 축이 하나도 없다 → 0단. 1로 올리지 않는다
						tuple("영속성 매핑과 지연 로딩", 0));
	}

	@Test
	@DisplayName("뷰의 reach_display_code를 그대로 썼다면 세 개념 모두 0단이 된다 — 회귀 방지")
	void viewColumnWouldCollapseEveryConceptIntoLevelZero() {
		assertThat(jdbc.queryForList("""
				SELECT reach_display_code FROM trainee_report_problem_view
				WHERE user_id = ? ORDER BY concept_display_order
				""", String.class, TRAINEE))
				.as("팀 공유 문제라 best_success_stage 가 NULL 로 강제된다")
				.containsExactly("L0", "L0", "L0");
	}

	private static org.assertj.core.groups.Tuple tuple(String name, int level) {
		return org.assertj.core.groups.Tuple.tuple(name, level);
	}

	private static void copyAndRun(Path script, String target) throws IOException, InterruptedException {
		POSTGRES.copyFileToContainer(MountableFile.forHostPath(script.toAbsolutePath()), target);
		Container.ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(),
				"-d", POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", target);
		assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
	}
}
