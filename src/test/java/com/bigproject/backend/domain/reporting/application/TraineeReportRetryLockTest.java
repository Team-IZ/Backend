package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.StageAnswerRow;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.ConceptReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TR-04가 <b>학생 본인의 답변을 언제 돌려주는가</b>를 못 박는다.
 *
 * <p>공개/비공개와 공개 범위(SUMMARY·FULL)가 폐지되면서(2026-08-19) 가림막은 하나만 남았다 —
 * <b>다시 보기 대상({@code level < 2})인데 아직 마치지 않았으면</b> {@code qa}와 {@code explain}이
 * 빠진다. 이 테스트는 그 하나가 제대로 걸리고 그 외에는 걸리지 않는 것을 확인한다.
 *
 * <p>🔴 이 규칙이 느슨해지면 응답은 200이고 화면도 정상이라 <b>어디서도 드러나지 않는다.</b>
 * 실제로 종전 코드가 그랬다 — {@code qa}는 막으면서 {@code explain}의 두 번째 줄로 같은 답변의
 * 발췌를 내보내고 있었다. 둘 다 {@code problem_stage}의 답변 컬럼에서 나온 값이다.
 *
 * @see RetryStateConsistencyTest 같은 판정이 retryState·레일 표시와 어긋나지 않는지 보는 테스트
 */
class TraineeReportRetryLockTest {

	/** 운영 기본값과 같은 다시 보기 창(일). 발행일로부터 이만큼 지나면 잠금이 풀린다. */
	private static final int REVIEW_WINDOW_DAYS = 3;

	private static final UUID USER = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();
	private static final UUID PROBLEM = UUID.randomUUID();
	private static final String ANSWER = "컨트롤러에 로직을 두면 테스트가 어려워져서 서비스로 분리했습니다.";
	private static final String EXCERPT = "서비스로 분리했습니다";

	/**
	 * 창이 아직 열려 있는 발행 시각. <b>고정 시각을 쓰지 않는다</b> — 판정이 {@code Instant.now()}
	 * 기준이라 고정값을 두면 그 날짜가 지나는 순간 테스트가 조용히 반대 결과를 확인하게 된다.
	 */
	private static final Instant PUBLISHED_RECENTLY = Instant.now().minus(Duration.ofDays(1));

	private static final Instant PUBLISHED_LONG_AGO = Instant.now().minus(Duration.ofDays(30));

	private TraineeReportQueryRepository queryRepository;
	private TraineeReportServiceImpl service;

	@BeforeEach
	void setUp() {
		queryRepository = mock(TraineeReportQueryRepository.class);
		service = new TraineeReportServiceImpl(queryRepository, new ObjectMapper(), REVIEW_WINDOW_DAYS);
	}

	/**
	 * 2단을 통과한 개념은 다시 볼 것이 없으므로 <b>발행 즉시</b> 답변과 해설이 열린다.
	 * 종전에는 여기에 공개 범위가 하나 더 걸려 있었다.
	 */
	@Test
	@DisplayName("통과한 개념은 발행 즉시 답변이 보인다")
	void showsAnswersOfPassedConceptsRightAfterPublish() {
		given(PUBLISHED_RECENTLY, noReview(), true);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNotNull();
		assertThat(concept.qa()).extracting(TraineeReportsResponse.QaEntryResponse::answer).contains(ANSWER);
	}

	/**
	 * 🔴 다시 보기 대상이면 <b>세션을 열기 전부터</b> 막는다.
	 *
	 * <p>종전 판정은 REVIEW 응시 행이 있어야 잠갔다. 그래서 다시 보기를 아예 안 연 학생은
	 * 답과 해설을 그대로 볼 수 있었고, <b>안 들어가는 쪽이 이득</b>이라 잠금이 목적을 잃었다.
	 */
	@Test
	@DisplayName("다시 보기 대상은 세션을 열기 전에도 답변과 해설이 가려진다")
	void hidesRetryTargetsEvenBeforeTheReviewSessionIsOpened() {
		given(PUBLISHED_RECENTLY, noReview(), false);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNull();
		assertThat(concept.explain())
				.as("explain 두 번째 줄이 답변 발췌라 qa 와 같은 기준으로 막아야 한다")
				.isNull();
	}

	/** 세션을 열어 두고 끝내지 않은 상태도 마찬가지다. 푸는 것은 완료뿐이다. */
	@Test
	@DisplayName("다시 보기를 시작만 하고 끝내지 않으면 계속 가려진다")
	void keepsHidingWhileTheReviewIsStillInProgress() {
		given(PUBLISHED_RECENTLY, reviewInProgress(), false);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNull();
		assertThat(concept.explain()).isNull();
	}

	/** 다시 보기를 마치면 학습 자료로 열어 준다. */
	@Test
	@DisplayName("다시 보기를 마치면 답변과 해설이 열린다")
	void showsAnswersAgainOnceTheRetryIsComplete() {
		given(PUBLISHED_RECENTLY, reviewCompleted(), false);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNotNull();
		assertThat(concept.explain()).isNotNull().anyMatch(line -> line.contains(EXCERPT));
	}

	/**
	 * 기한이 지나면 다시 풀 방법 자체가 없어진다. 막을 것이 없어졌으므로 학습 자료로 열어 준다
	 * (2026-08-19 결정 — 영구 잠금은 하지 않는다).
	 */
	@Test
	@DisplayName("다시 보기 창이 닫히면 못 한 학생에게도 열어 준다")
	void unlocksOnceTheReviewWindowHasClosed() {
		given(PUBLISHED_LONG_AGO, noReview(), false);

		ConceptReportResponse concept = firstConcept();

		assertThat(concept.qa()).isNotNull();
		assertThat(concept.explain()).isNotNull();
	}

	/**
	 * 🔴 매니저는 잠금을 받지 않는다. 잠금의 목적이 "학생이 답을 먼저 보고 다시 푸는 것"을 막는
	 * 것이라 매니저에게는 해당이 없고, 지도하려면 학생이 뭐라고 답했는지를 봐야 한다.
	 */
	@Test
	@DisplayName("매니저 조회에서는 다시 보기 전이어도 답변과 해설이 나온다")
	void doesNotLockAnythingForTheManagerView() {
		given(PUBLISHED_RECENTLY, noReview(), false);

		ConceptReportResponse concept = service.findTraineeReportsForManager(USER)
				.reportsById().get(ROUND.toString()).concepts().get(0);

		assertThat(concept.qa()).isNotNull();
		assertThat(concept.explain()).isNotNull();
	}

	/** 잠금은 개념 단위다 — 한 문제가 대상이라고 통과한 문제까지 닫히면 안 된다. */
	@Test
	@DisplayName("다시 보기 대상이 아닌 개념은 같은 회차에 대상이 있어도 그대로 보인다")
	void doesNotHidePassedProblemsJustBecauseAnotherProblemNeedsRetry() {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(round(PUBLISHED_RECENTLY, noReview())));
		when(queryRepository.findConcepts(USER)).thenReturn(List.of(
				concept(UUID.randomUUID(), 1),
				concept(PROBLEM, 2)));
		when(queryRepository.findStageAnswers(USER)).thenReturn(List.of(answer()));

		List<ConceptReportResponse> concepts =
				service.findMyReports(USER).reportsById().get(ROUND.toString()).concepts();

		assertThat(concepts.get(0).qa()).as("1단 — 다시 보기 대상이라 막힌다").isNull();
		assertThat(concepts.get(1).qa()).as("2단 — 통과했으므로 열린다").isNotNull();
	}

	// ------------------------------------------------------------------ fixture

	private ConceptReportResponse firstConcept() {
		TraineeReportsResponse response = service.findMyReports(USER);
		return response.reportsById().get(ROUND.toString()).concepts().get(0);
	}

	/** REVIEW 응시의 조회 결과. {@code status}가 null이면 배정 자체가 없다. */
	private record Review(String status, Instant completedAt) {
	}

	private static Review noReview() {
		return new Review(null, null);
	}

	private static Review reviewInProgress() {
		return new Review("SESSION_READY", null);
	}

	private static Review reviewCompleted() {
		return new Review("COMPLETED", Instant.now().minus(Duration.ofHours(2)));
	}

	/**
	 * @param publishedAt 발행 시각. 다시 보기 창의 기산점이라 잠금 판정을 좌우한다
	 * @param passed      이 개념이 2단을 통과했는가. <b>도달 단계로 표현한다</b> —
	 *                    대상 판정은 저장된 값이 아니라 "2단 미만" 정책에서 나온다(23차 R2)
	 */
	private void given(Instant publishedAt, Review review, boolean passed) {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(round(publishedAt, review)));
		when(queryRepository.findConcepts(USER)).thenReturn(List.of(concept(PROBLEM, passed ? 2 : 1)));
		when(queryRepository.findStageAnswers(USER)).thenReturn(List.of(answer()));
	}

	private static RoundRow round(Instant publishedAt, Review review) {
		return new RoundRow(
				ROUND, "미프 1차 이해도 확인", 1, "미니프로젝트",
				REPORT, UUID.randomUUID(), "FULL",
				// sampleCount 3 · missingCount 0 — 생성 실패가 없는 정상 리포트다(19차 Q1).
				3, 0,
				UUID.randomUUID(), "COMPLETED", null, "APPROVED",
				null, null, publishedAt,
				review.status(),
				review.status() == null ? null : publishedAt.plus(Duration.ofDays(REVIEW_WINDOW_DAYS)),
				review.completedAt());
	}

	// reviewRequired 컬럼 값은 더 이상 쓰이지 않으므로 일부러 정책과 반대로 넣어 둔다 —
	// 저장된 값이 되살아나면 여기서 깨진다.
	private static ConceptRow concept(UUID problemId, int level) {
		return new ConceptRow(
				REPORT, problemId, "트랜잭션 경계 설정", 1, level,
				"선택 이유까지는 설명했지만 대안은 제시하지 못했습니다.", EXCERPT,
				null, level < 2, null, true);
	}

	private static StageAnswerRow answer() {
		return new StageAnswerRow(REPORT, PROBLEM, "L2", 1, "QUESTION", "왜 이 구조를 선택했나요?", ANSWER);
	}
}
