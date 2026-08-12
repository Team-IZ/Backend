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
		// findConcepts 가 SELECT 하는 컬럼 전량.
		assertThat(views).contains(
				"concept_display_name", "concept_display_order", "reach_display_code",
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
	}

	/**
	 * 문항 없음이 {@code L0}로 접히지 않는지를 <b>정본 정의 자체</b>로 확인한다.
	 *
	 * <p>뷰가 {@code reach_display_code}를 {@code COALESCE(best_success_stage,'L0')}으로 만들고
	 * {@code best_success_stage}는 {@code NOT_GENERATED}일 때 NULL이 강제된다
	 * ({@code ck_assessment_problem_best_success_stage_3}). 즉 <b>묻지 못한 개념도 뷰에서는 L0로
	 * 보인다</b> — 그래서 {@code findUnaskedConcepts}가 따로 필요하다. 이 사실이 뒤집히면
	 * (뷰가 문항 없음을 스스로 구분하게 되면) 그 별도 조회는 중복이 되므로 여기서 알아채야 한다.
	 */
	@Test
	@DisplayName("뷰의 reach_display_code는 문항 없음과 0단을 구분하지 못한다 — 별도 조회가 필요한 근거")
	void reachDisplayCodeCollapsesUnaskedIntoLevelZero() throws IOException {
		assumeTrue(Files.exists(DDL) && Files.exists(VIEWS), "정본 DDL/View 문서가 없어 계약 검증을 건너뜁니다.");

		assertThat(Files.readString(VIEWS))
				.as("뷰가 문항 없음을 L0로 접는다")
				.contains("COALESCE(ap.best_success_stage,'L0')");
		assertThat(Files.readString(DDL))
				.as("NOT_GENERATED 문제는 best_success_stage가 NULL로 강제된다")
				.contains("ck_assessment_problem_best_success_stage_3");
	}
}
