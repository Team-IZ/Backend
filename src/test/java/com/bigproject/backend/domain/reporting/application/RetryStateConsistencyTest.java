package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
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
 * {@code retryState} · {@code rounds[].hasPendingRetry} · {@code isRetryTarget}이
 * <b>같은 원천에서 나온다</b>는 것을 못 박는다.
 *
 * <p>이 테스트가 있는 이유는 세 값이 실제로 서로 다른 말을 한 적이 있기 때문이다.
 * 대상 판정은 23차에 <b>2단 미만</b>으로 확정됐는데 {@code retryState}는 REVIEW 응시 행의
 * 존재 여부만 보고 있어서, <b>다시 볼 문제가 0개인 회차가 {@code PENDING}</b>으로 나갔다.
 * 화면은 "다시 볼 수 있는 문제가 0개 있어요" 배너를 띄우고 그 버튼은 빈 세션으로 들어갔다(24차 R1).
 *
 * @see RetryTargetPolicyTest 대상 판정(2단 미만) 자체를 못 박는 테스트
 * @see TraineeReportRetryLockTest 그 상태가 실제로 답변·해설을 가리는지 보는 테스트
 */
class RetryStateConsistencyTest {

	/** 운영 기본값과 같은 다시 보기 창(일). 발행일로부터 이만큼 지나면 잠금이 풀린다. */
	private static final int REVIEW_WINDOW_DAYS = 3;

	private static final UUID USER = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();

	/**
	 * 창이 아직 열려 있는 발행 시각. <b>고정 시각을 쓰지 않는다</b> — 판정이 {@code Instant.now()}
	 * 기준이라 고정값을 두면 그 날짜가 지나는 순간 테스트가 조용히 반대 결과를 확인하게 된다.
	 */
	private static final Instant PUBLISHED_RECENTLY = Instant.now().minus(Duration.ofDays(1));

	/** 창이 닫힌 발행 시각. {@link #REVIEW_WINDOW_DAYS}보다 확실히 과거다. */
	private static final Instant PUBLISHED_LONG_AGO = Instant.now().minus(Duration.ofDays(30));

	private static final Instant REVIEW_DONE_AT = Instant.now().minus(Duration.ofHours(2));

	private TraineeReportQueryRepository queryRepository;
	private TraineeReportServiceImpl service;

	@BeforeEach
	void setUp() {
		queryRepository = mock(TraineeReportQueryRepository.class);
		service = new TraineeReportServiceImpl(queryRepository, new ObjectMapper(), REVIEW_WINDOW_DAYS);
	}

	/**
	 * trainee01의 2차 리포트다. 도달 단계가 3·2·2라 대상이 하나도 없는데 REVIEW 응시 행은 남아 있다 —
	 * 2단 통과 개념까지 대상으로 잡던 시절에 만들어진 행이다.
	 */
	@Test
	@DisplayName("다시 볼 개념이 0개면 REVIEW 응시가 미완료여도 PENDING이 아니다")
	void doesNotPromiseARetryThatHasNothingToRetry() {
		given(reviewInProgress(), PUBLISHED_RECENTLY, concept("예외 처리와 롤백 전략", 3),
				concept("API 응답 계약 설계", 2), concept("영속성 매핑과 지연 로딩", 2));

		TraineeReportsResponse response = service.findMyReports(USER);

		assertThat(report(response).retryState()).isEqualTo("NONE");
		assertThat(response.rounds().get(0).hasPendingRetry()).isFalse();
	}

	@Test
	@DisplayName("다시 볼 개념이 있고 다시 보기를 마치지 않았으면 PENDING이다")
	void reportsPendingWhenSomethingIsActuallyLeftToRetry() {
		given(reviewInProgress(), PUBLISHED_RECENTLY, concept("트랜잭션 경계 설정", 1),
				concept("계층 분리와 의존성 방향", 3));

		TraineeReportsResponse response = service.findMyReports(USER);

		assertThat(report(response).retryState()).isEqualTo("PENDING");
		assertThat(response.rounds().get(0).hasPendingRetry()).isTrue();
	}

	/** 이미 일어난 사실의 기록이라 지금 대상이 0개여도 참이다. 이 값이 답변·해설 잠금을 푼다. */
	@Test
	@DisplayName("마친 다시 보기는 대상이 0개여도 DONE으로 남는다")
	void keepsCompletedReviewsVisibleEvenWithoutRemainingTargets() {
		given(reviewCompleted(), PUBLISHED_RECENTLY, concept("예외 처리와 롤백 전략", 3),
				concept("API 응답 계약 설계", 2));

		TraineeReportsResponse response = service.findMyReports(USER);

		assertThat(report(response).retryState()).isEqualTo("DONE");
		assertThat(response.rounds().get(0).hasPendingRetry()).isFalse();
	}

	/**
	 * 🔴 <b>종전과 반대 결과다.</b> 예전 판정은 REVIEW 응시 행이 없으면({@code reviewStatus == null})
	 * 곧바로 {@code NONE}이었다. 그래서 다시 보기를 <b>열지 않은</b> 학생은 할 일도 안 보이고
	 * 답과 해설까지 그대로 열려 있었다 — 안 들어가는 쪽이 이득이라 잠금이 목적을 잃는다.
	 *
	 * <p>이제는 다시 볼 문제가 있다는 사실만으로 {@code PENDING}이다. 세션을 열었는지는 보지 않는다.
	 */
	@Test
	@DisplayName("다시 보기를 아직 열지 않았어도 대상이 있으면 PENDING이다")
	void reportsPendingBeforeTheTraineeEvenOpensTheReview() {
		given(noReview(), PUBLISHED_RECENTLY, concept("트랜잭션 경계 설정", 1));

		TraineeReportsResponse response = service.findMyReports(USER);

		assertThat(report(response).retryState()).isEqualTo("PENDING");
		assertThat(response.rounds().get(0).hasPendingRetry()).isTrue();
	}

	/**
	 * 기한이 지나면 다시 볼 방법 자체가 없어지므로 할 일도 만들지 않는다.
	 * 들어갈 수 없는 세션의 시작 버튼을 그리면 안 된다 — 대상이었다는 사실은
	 * 개념 카드의 {@code isRetryTarget}에 그대로 남는다.
	 */
	@Test
	@DisplayName("다시 보기 창이 닫히면 대상이 남아 있어도 NONE이다")
	void stopsPromisingARetryOnceTheWindowClosed() {
		given(noReview(), PUBLISHED_LONG_AGO, concept("트랜잭션 경계 설정", 1));

		TraineeReportsResponse response = service.findMyReports(USER);

		assertThat(report(response).retryState()).isEqualTo("NONE");
		assertThat(response.rounds().get(0).hasPendingRetry()).isFalse();
		assertThat(report(response).concepts().get(0).isRetryTarget()).isTrue();
	}

	/** 세 값이 갈리면 화면 한 곳에서 서로 다른 말을 한다 — 배너·레일·본문이 각각 다른 값을 읽는다. */
	@Test
	@DisplayName("PENDING과 hasPendingRetry와 isRetryTarget 존재 여부는 언제나 함께 움직인다")
	void railBannerAndConceptsNeverContradictEachOther() {
		given(reviewInProgress(), PUBLISHED_RECENTLY, concept("a", 0), concept("b", 2), concept("c", 3));

		TraineeReportsResponse response = service.findMyReports(USER);
		boolean anyTarget = report(response).concepts().stream()
				.anyMatch(TraineeReportsResponse.ConceptReportResponse::isRetryTarget);

		assertThat(anyTarget).isTrue();
		assertThat(report(response).retryState()).isEqualTo("PENDING");
		assertThat(response.rounds().get(0).hasPendingRetry()).isTrue();
	}

	// ------------------------------------------------------------------ fixture

	private static TraineeReportsResponse.RoundReportResponse report(TraineeReportsResponse response) {
		return response.reportsById().get(ROUND.toString());
	}

	private static ConceptRow concept(String name, int level) {
		// 저장된 decision_code는 일부러 정책과 어긋나게 둔다 — 읽는 쪽이 도달 단계로 덮는지 본다.
		return new ConceptRow(REPORT, UUID.randomUUID(), name, 1, level,
				"결과 설명", "발췌", null, true, null, true);
	}

	/** REVIEW 응시의 조회 결과. {@code status}가 null이면 배정 자체가 없다. */
	private record Review(String status, Instant completedAt) {
	}

	private static Review reviewInProgress() {
		return new Review("SESSION_READY", null);
	}

	private static Review reviewCompleted() {
		return new Review("COMPLETED", REVIEW_DONE_AT);
	}

	private static Review noReview() {
		return new Review(null, null);
	}

	private void given(Review review, Instant publishedAt, ConceptRow... concepts) {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(new RoundRow(
				ROUND, "미니프로젝트 2차 이해도 확인", 1, "미니프로젝트 2차",
				REPORT, UUID.randomUUID(), "FULL", 3, 0,
				UUID.randomUUID(), "COMPLETED", null, "NOT_REQUIRED",
				null, null, publishedAt,
				review.status(),
				review.status() == null ? null : publishedAt.plus(Duration.ofDays(REVIEW_WINDOW_DAYS)),
				review.completedAt())));
		when(queryRepository.findConcepts(USER)).thenReturn(List.of(concepts));
	}
}
