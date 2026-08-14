package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.StageAnswerRow;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.ConceptReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TR-04가 <b>학생 본인의 답변을 언제 돌려주는가</b>를 못 박는다.
 *
 * <p>이 규칙이 느슨해지면 응답은 200이고 화면도 정상이라 <b>어디서도 드러나지 않는다.</b>
 * 실제로 종전 코드가 그랬다 — {@code SUMMARY}에서 {@code qa}는 막으면서
 * {@code explain}의 두 번째 줄로 같은 답변의 발췌를 내보내고 있었다. 둘 다
 * {@code problem_stage}의 답변 컬럼에서 나온 값이다.
 */
class TraineeReportDisclosureGatingTest {

	private static final UUID USER = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();
	private static final UUID PROBLEM = UUID.randomUUID();
	private static final String ANSWER = "컨트롤러에 로직을 두면 테스트가 어려워져서 서비스로 분리했습니다.";
	private static final String EXCERPT = "서비스로 분리했습니다";

	private TraineeReportQueryRepository queryRepository;
	private TraineeReportServiceImpl service;

	@BeforeEach
	void setUp() {
		queryRepository = mock(TraineeReportQueryRepository.class);
		service = new TraineeReportServiceImpl(queryRepository, new ObjectMapper());
	}

	/** {@code FULL} · 다시 보기 없음 — 아무것도 막지 않는다. 아래 케이스들의 대조군이다. */
	@Test
	void showsAnswersWhenFullyDisclosedAndNoRetryIsPending() {
		given("FULL", null, null, true);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNotNull();
		assertThat(concept.qa()).extracting(TraineeReportsResponse.QaEntryResponse::answer).contains(ANSWER);
	}

	/**
	 * 🔴 {@code SUMMARY}에서는 {@code qa}도 {@code explain}도 나가지 않는다.
	 *
	 * <p>{@code explain}까지 보는 이유는 그 두 번째 줄이 <b>답변 발췌</b>이기 때문이다.
	 * {@code qa}만 막으면 같은 답변이 다른 필드로 나간다.
	 */
	@Test
	void hidesBothAnswersAndExplanationWhenScopeIsSummary() {
		given("SUMMARY", null, null, true);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNull();
		assertThat(concept.explain())
				.as("explain 두 번째 줄이 답변 발췌라 qa 와 같은 기준으로 막아야 한다")
				.isNull();
	}

	/**
	 * 🔴 다시 보기를 <b>아직 안 했으면</b> {@code FULL}이어도 막는다.
	 *
	 * <p>다시 풀어야 할 문제의 답과 해설을 먼저 보여주면 다시 보기가 성립하지 않는다.
	 * 매니저가 전부 공개했다는 것과 "이 문제를 다시 풀어야 한다"는 별개다.
	 */
	@Test
	void hidesAnswersOfRetryTargetsUntilTheRetryIsDone() {
		given("FULL", "IN_PROGRESS", null, true);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNull();
		assertThat(concept.explain()).isNull();
	}

	/** 다시 보기를 마치면 학습 자료로 열어 준다. */
	@Test
	void showsAnswersAgainOnceTheRetryIsComplete() {
		given("FULL", "COMPLETED", Instant.parse("2026-08-01T00:00:00Z"), true);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNotNull();
		assertThat(concept.explain()).isNotNull().anyMatch(line -> line.contains(EXCERPT));
	}

	/** 다시 보기 대상이 <b>아닌</b> 문제는 다시 보기가 걸려 있어도 그대로 보인다. */
	@Test
	void doesNotHidePassedProblemsJustBecauseAnotherProblemNeedsRetry() {
		given("FULL", "IN_PROGRESS", null, false);

		assertThat(firstConcept().qa()).isNotNull();
	}

	// ------------------------------------------------------------------ fixture

	private ConceptReportResponse firstConcept() {
		TraineeReportsResponse response = service.findMyReports(USER);
		return response.reportsById().get(ROUND.toString()).concepts().get(0);
	}

	/**
	 * @param scope         {@code trainee_disclosure_scope}
	 * @param reviewStatus  다시 보기 응시 상태. null이면 다시 보기가 없다
	 * @param reviewDone    다시 보기 종료 시각. null이면 진행 중이다
	 * @param retryTarget   이 개념이 다시 보기 대상인가. <b>도달 단계로 표현한다</b> —
	 *                      대상 판정은 저장된 값이 아니라 "2단 미만" 정책에서 나온다(23차 R2)
	 */
	private void given(String scope, String reviewStatus, Instant reviewDone, boolean retryTarget) {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(new RoundRow(
				ROUND, "미프 1차 이해도 확인", 1, "미니프로젝트",
				REPORT, UUID.randomUUID(), "FULL",
				// sampleCount 3 · missingCount 0 — 생성 실패가 없는 정상 리포트다(19차 Q1).
				3, 0,
				UUID.randomUUID(), "COMPLETED", null, "APPROVED",
				"RELEASED", scope,
				null, null, Instant.parse("2026-07-01T00:00:00Z"), true,
				reviewStatus, null, reviewDone)));

		// 대상이면 1단(불합격), 아니면 2단(합격선). reviewRequired 컬럼 값은 더 이상 쓰이지 않으므로
		// 일부러 정책과 반대로 넣어 둔다 — 저장된 값이 되살아나면 여기서 깨진다.
		when(queryRepository.findConcepts(USER)).thenReturn(List.of(new ConceptRow(
				REPORT, PROBLEM, "트랜잭션 경계 설정", 1, retryTarget ? 1 : 2,
				"선택 이유까지는 설명했지만 대안은 제시하지 못했습니다.", EXCERPT,
				null, !retryTarget, null, true)));

		when(queryRepository.findStageAnswers(USER)).thenReturn(List.of(new StageAnswerRow(
				REPORT, PROBLEM, "L2", 1, "QUESTION", "왜 이 구조를 선택했나요?", ANSWER)));
	}
}
