package com.bigproject.backend.domain.reporting.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 교육생 리포트 4개 오퍼레이션이 읽는 원천이 정본 문서에 실제로 있는지 확인한다.
 *
 * <p>이 테스트가 있는 이유는 {@code x-readiness} 때문이다. 리포트 오퍼레이션은
 * "실제 DB로 검증되지 않았다"는 이유로 {@code ⚠️ 사용 불가}로 묶여 있었고, 프론트 코드 생성기가
 * 그 표시를 보고 호출 함수를 아예 만들지 않는다. 표시를 올리려면 SQL이 도는 것을 확인해야 하는데,
 * 라이브 DB 없이 확인할 수 있는 최대치가 <b>정본 DDL·View에 컬럼이 있는가</b>이다.
 *
 * <p>{@code ManagerViewCanonicalSqlContractTest}와 같은 방식이며, 잡는 것도 같다 — 뷰가 개정돼
 * 컬럼 이름이 바뀌면 여기서 먼저 깨진다. 잡지 못하는 것은 조인 결과의 의미론이다.
 */
class TraineeReportCanonicalSqlContractTest {

	private static final Path DDL = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");
	private static final Path VIEWS = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_View.sql");

	@Test
	@DisplayName("리포트 조회 SQL이 읽는 뷰 컬럼이 정본 View 문서에 있다")
	void canonicalViewsContainEveryTraineeReportSource() throws IOException {
		assumeTrue(Files.exists(DDL) && Files.exists(VIEWS), "정본 DDL/View 문서가 없어 계약 검증을 건너뜁니다.");

		String views = Files.readString(VIEWS);

		assertThat(views).contains("trainee_report_problem_view");
		// findConcepts 가 SELECT 하는 컬럼 전량. reach_display_code 는 일부러 빠져 있다 —
		// 미니프로젝트에서 항상 L0라 도달 단계를 report_evidence·problem_stage 에서 다시 읽는다.
		assertThat(views).contains(
				"concept_display_name", "concept_display_order", "snapshot_id",
				"result_explanation", "answer_excerpt", "curriculum_location",
				"review_required", "review_before_after_items", "can_view_explanation");
	}

	@Test
	@DisplayName("리포트 조회 SQL이 읽는 베이스 테이블 컬럼이 정본 DDL에 있다")
	void canonicalDdlContainsEveryBaseTableColumn() throws IOException {
		assumeTrue(Files.exists(DDL) && Files.exists(VIEWS), "정본 DDL/View 문서가 없어 계약 검증을 건너뜁니다.");

		String ddl = Files.readString(DDL);

		// findRounds — 뷰가 리포트 없는 회차를 못 내서 베이스 테이블을 직접 탄다(레포지토리 주석 참고).
		assertThat(ddl).contains(
				"trainee_release_status", "trainee_disclosure_scope", "lifecycle_status",
				"report_publish_not_before_at", "validity_review_status", "terminal_reason_code");

		// findUnaskedConcepts — 문항 없음(제3의 값)을 0단과 가르는 원천.
		assertThat(ddl).contains(
				"generation_status", "not_generated_reason_code", "project_verification_concept_id",
				"code_analysis_id");

		// findStageAnswers — 질문 1 + 힌트 2 = 최대 3슬롯.
		assertThat(ddl).contains(
				"question_answer_text", "first_hint_answer_text", "second_hint_answer_text");

		// findConcepts 의 도달 단계 폴백 — 뷰 대신 problem_stage 를 본인 INITIAL 세션으로 좁혀 탄다.
		assertThat(ddl).contains("trace_payload", "evidence_category", "axis_code", "attempt_type");
	}

	/**
	 * 뷰의 {@code reach_display_code}를 <b>쓰면 안 되는 근거</b>를 정본 정의 자체로 고정한다.
	 *
	 * <p>뷰는 그 값을 {@code COALESCE(best_success_stage,'L0')}으로 만드는데, DDL의 두 CHECK가
	 * {@code best_success_stage}를 NULL로 강제한다.
	 * <ul>
	 *   <li>{@code ..._2} — {@code problem_scope='TEAM_SHARED_PROBLEM'}. <b>미니프로젝트 전량</b>이라
	 *       모든 개념이 0단으로 나간다(20차 R2). 팀이 공유하는 것은 문제이고 도달 단계는 사람마다 다르다</li>
	 *   <li>{@code ..._3} — {@code generation_status='NOT_GENERATED'}. 묻지 못한 개념도 L0로 보여
	 *       {@code findUnaskedConcepts}가 따로 필요하다</li>
	 * </ul>
	 *
	 * <p>둘 중 하나라도 사라지거나 뷰가 다른 원천을 보게 되면 여기서 먼저 깨진다 — 그때는
	 * {@code findConcepts}의 우회(evidence·problem_stage)를 걷어내고 컬럼 하나로 되돌릴 수 있다.
	 */
	@Test
	@DisplayName("뷰의 reach_display_code는 팀 공유 문제와 문항 없음을 모두 0단으로 접는다 — 우회의 근거")
	void reachDisplayCodeCollapsesEveryMiniProjectConceptIntoLevelZero() throws IOException {
		assumeTrue(Files.exists(DDL) && Files.exists(VIEWS), "정본 DDL/View 문서가 없어 계약 검증을 건너뜁니다.");

		assertThat(Files.readString(VIEWS))
				.as("뷰가 도달 단계를 best_success_stage 하나로 만든다")
				.contains("COALESCE(ap.best_success_stage,'L0')");

		String ddl = Files.readString(DDL);
		assertThat(ddl)
				.as("팀 공유 문제는 best_success_stage가 NULL로 강제된다 — 미니프로젝트 전량이다")
				.contains("ck_assessment_problem_best_success_stage_2");
		assertThat(ddl)
				.as("NOT_GENERATED 문제도 best_success_stage가 NULL로 강제된다")
				.contains("ck_assessment_problem_best_success_stage_3");
	}
}
