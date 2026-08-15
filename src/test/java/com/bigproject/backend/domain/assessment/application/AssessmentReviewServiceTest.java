package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.ReviewModels.ExistingReview;
import com.bigproject.backend.domain.assessment.domain.ReviewModels.ReviewSource;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionReviewRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 다시 보기 파생 규칙을 고정한다.
 *
 * <p>여기서 지키는 것은 셋이다 — <b>2단 미만만 대상</b>이고, <b>회차당 한 번</b>이며,
 * <b>물어볼 것이 없는 세션은 만들지 않는다</b>. 셋 다 어기면 화면이 아니라 데이터가 망가진다:
 * 응시가 둘 생기면 도달 단계 비교가 어느 쪽을 봐야 하는지 알 수 없고, 단계가 0건인 세션은
 * 학생이 전체화면으로 들어간 뒤 시작에서 막힌다.
 */
class AssessmentReviewServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID REPORT_ID = UUID.randomUUID();
	private static final UUID SNAPSHOT_ID = UUID.randomUUID();
	private static final UUID ROUND_ID = UUID.randomUUID();
	private static final UUID SOURCE_ATTEMPT_ID = UUID.randomUUID();
	private static final UUID SOURCE_SESSION_ID = UUID.randomUUID();
	private static final UUID NEW_ATTEMPT_ID = UUID.randomUUID();
	private static final UUID NEW_SESSION_ID = UUID.randomUUID();

	private JdbcSessionReviewRepository reviewRepository;
	private JdbcSessionRepository sessionRepository;
	private AssessmentReviewService service;

	@BeforeEach
	void setUp() {
		reviewRepository = mock(JdbcSessionReviewRepository.class);
		sessionRepository = mock(JdbcSessionRepository.class);
		service = new AssessmentReviewService(reviewRepository, sessionRepository);
		ReflectionTestUtils.setField(service, "reviewWindowDays", 7);
	}

	// ── 새로 개설 ──

	/** 1차가 끝났고 2단 미만인 문제가 있으면 응시·세션·단계 복사가 한 벌로 일어난다. */
	@Test
	void 대상이_있으면_응시와_세션을_만들고_1차_단계를_복사한다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.empty());
		when(reviewRepository.countReviewTargets(SOURCE_SESSION_ID,
				AssessmentReviewService.REVIEW_TARGET_BELOW_LEVEL)).thenReturn(2);
		givenInsertSucceeds(8);
		givenReadableSession("READY");

		SessionResponse response = service.openReview(USER_ID, REPORT_ID);

		assertThat(response.sessionId()).isEqualTo(NEW_SESSION_ID);
		assertThat(response.mode()).isEqualTo("REVIEW");
		verify(reviewRepository).insertReviewAttempt(eqSourceAttempt(), any(), any(), any(), any());
		verify(reviewRepository).copyStagesForReview(NEW_SESSION_ID, SOURCE_SESSION_ID,
				AssessmentReviewService.REVIEW_TARGET_BELOW_LEVEL);
	}

	/**
	 * 🔴 기준값은 <b>2</b>다. 리포트가 `다시 볼 개념`으로 표시하는 기준
	 * ({@code TraineeReportServiceImpl.RETRY_TARGET_BELOW_LEVEL})과 같아야 한다 — 어긋나면 학생이
	 * 리포트에서 본 개념과 다른 문제를 풀게 된다.
	 */
	@Test
	void 대상_기준은_2단_미만이다() {
		assertThat(AssessmentReviewService.REVIEW_TARGET_BELOW_LEVEL).isEqualTo(2);
	}

	// ── 열지 않는 경우 ──

	/** 남의 리포트·미공개 리포트는 조회가 비므로 404다. 넷을 구분하지 않는다. */
	@Test
	void 접근할_수_없는_리포트면_열지_않는다() {
		when(reviewRepository.findReviewSource(REPORT_ID, USER_ID)).thenReturn(Optional.empty());

		assertErrorCode(SessionErrorCode.REVIEW_REPORT_NOT_ACCESSIBLE);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
	}

	/** 1차가 끝나지 않았으면 도달 단계가 확정되지 않아 다시 볼 문제를 고를 수 없다. */
	@Test
	void 초기_응시가_끝나지_않았으면_열지_않는다() {
		givenAccessibleReport(new ReviewSource(REPORT_ID, SNAPSHOT_ID, ROUND_ID, SOURCE_ATTEMPT_ID,
				"SESSION_IN_PROGRESS", SOURCE_SESSION_ID));
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.empty());

		assertErrorCode(SessionErrorCode.REVIEW_SOURCE_NOT_READY);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
	}

	/** 분석이 실패해 1차 세션이 열리지 않았으면 가져올 질문 자체가 없다. */
	@Test
	void 초기_세션이_없으면_열지_않는다() {
		givenAccessibleReport(new ReviewSource(REPORT_ID, SNAPSHOT_ID, ROUND_ID, SOURCE_ATTEMPT_ID,
				"COMPLETED", null));
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.empty());

		assertErrorCode(SessionErrorCode.REVIEW_SOURCE_NOT_READY);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
	}

	/** 전부 2단 이상이면 다시 볼 것이 없다. 오류가 아니라 안내이므로 응시를 만들지 않고 끊는다. */
	@Test
	void 기준_단계_미만인_문제가_없으면_열지_않는다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.empty());
		when(reviewRepository.countReviewTargets(SOURCE_SESSION_ID,
				AssessmentReviewService.REVIEW_TARGET_BELOW_LEVEL)).thenReturn(0);

		assertErrorCode(SessionErrorCode.REVIEW_NOT_ELIGIBLE);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
	}

	/**
	 * 세는 것과 복사하는 것 사이에 원본이 사라져 <b>단계가 0건</b>이면 세션을 남기지 않는다.
	 *
	 * <p>물어볼 것이 없는 세션이 남으면 화면은 전체화면으로 들어간 뒤 시작에서 막힌다. 예외를 던져
	 * 트랜잭션을 통째로 되돌리는 것이 이 검사의 존재 이유다.
	 */
	@Test
	void 복사가_0건이면_세션을_남기지_않는다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.empty());
		when(reviewRepository.countReviewTargets(SOURCE_SESSION_ID,
				AssessmentReviewService.REVIEW_TARGET_BELOW_LEVEL)).thenReturn(2);
		givenInsertSucceeds(0);

		assertErrorCode(SessionErrorCode.REVIEW_NOT_ELIGIBLE);
	}

	// ── 회차당 한 번 ──

	/**
	 * 두 번 눌러도 응시를 둘 만들지 않는다. 새로고침 뒤 `다시 보기 시작`이 다시 눌리는 것이 정상 흐름이라
	 * 멱등해야 한다.
	 */
	@Test
	void 이미_열려_있으면_새로_만들지_않고_그것을_돌려준다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.of(
				new ExistingReview(NEW_ATTEMPT_ID, "SESSION_IN_PROGRESS", NEW_SESSION_ID, "IN_PROGRESS")));
		givenReadableSession("IN_PROGRESS");

		assertThat(service.openReview(USER_ID, REPORT_ID).sessionId()).isEqualTo(NEW_SESSION_ID);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
		verify(reviewRepository, never()).insertReviewSession(any());
	}

	/** 끝낸 다시 보기는 되살리지 않는다 — 회차당 한 번이다. */
	@Test
	void 이미_끝낸_다시_보기는_다시_열지_않는다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.of(
				new ExistingReview(NEW_ATTEMPT_ID, "COMPLETED", NEW_SESSION_ID, "COMPLETED")));

		assertErrorCode(SessionErrorCode.REVIEW_ALREADY_COMPLETED);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
	}

	/**
	 * 🔴 <b>끝난 세션이 붙은 응시에 세션을 하나 더 넣지 않는다.</b>
	 *
	 * <p>{@code uq_assessment_session_attempt_id}가 응시당 세션을 하나로 묶는다. 응시 상태가 따라오지
	 * 않아 "끝나지 않았다"로 보이는 행에 세션을 채우려 들면 무결성 위반으로 500이 난다 — 학생에게는
	 * 원인을 알 수 없는 서버 오류로 보인다.
	 */
	@Test
	void 끝난_세션이_붙어_있으면_세션을_더_만들지_않는다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.of(
				new ExistingReview(NEW_ATTEMPT_ID, "SESSION_READY", NEW_SESSION_ID, "INTERRUPTED")));

		assertErrorCode(SessionErrorCode.REVIEW_ALREADY_COMPLETED);
		verify(reviewRepository, never()).insertReviewSession(any());
	}

	/** 응시만 있고 세션이 없으면(중간에 끊긴 흔적) 응시를 새로 만들지 않고 세션만 채워 이어 간다. */
	@Test
	void 세션이_없는_응시는_세션만_채워_이어_간다() {
		givenAccessibleReport(completedSource());
		when(reviewRepository.findExistingReview(ROUND_ID, USER_ID)).thenReturn(Optional.of(
				new ExistingReview(NEW_ATTEMPT_ID, "SESSION_READY", null, null)));
		when(reviewRepository.insertReviewSession(NEW_ATTEMPT_ID)).thenReturn(NEW_SESSION_ID);
		when(reviewRepository.copyStagesForReview(any(), any(), anyInt())).thenReturn(8);
		givenReadableSession("READY");

		assertThat(service.openReview(USER_ID, REPORT_ID).sessionId()).isEqualTo(NEW_SESSION_ID);
		verify(reviewRepository, never()).insertReviewAttempt(any(), any(), any(), any(), any());
		verify(reviewRepository).insertReviewSession(NEW_ATTEMPT_ID);
	}

	// ── 픽스처 ──

	private static ReviewSource completedSource() {
		return new ReviewSource(REPORT_ID, SNAPSHOT_ID, ROUND_ID, SOURCE_ATTEMPT_ID, "COMPLETED",
				SOURCE_SESSION_ID);
	}

	private void givenAccessibleReport(ReviewSource source) {
		when(reviewRepository.findReviewSource(REPORT_ID, USER_ID)).thenReturn(Optional.of(source));
	}

	private void givenInsertSucceeds(int copiedStages) {
		when(reviewRepository.insertReviewAttempt(any(), any(), any(), any(), any()))
				.thenReturn(NEW_ATTEMPT_ID);
		when(reviewRepository.insertReviewSession(NEW_ATTEMPT_ID)).thenReturn(NEW_SESSION_ID);
		when(reviewRepository.copyStagesForReview(any(), any(), anyInt())).thenReturn(copiedStages);
	}

	/** 만든 직후의 세션을 {@code GET /current}와 같은 모양으로 읽는 부분. */
	private void givenReadableSession(String status) {
		when(sessionRepository.findOwned(NEW_SESSION_ID, USER_ID)).thenReturn(Optional.of(new SessionHead(
				NEW_SESSION_ID, UUID.randomUUID(), NEW_ATTEMPT_ID, USER_ID, ROUND_ID,
				"REVIEW", status, null, null, null, null, Instant.now(), UUID.randomUUID())));
		when(sessionRepository.findStages(NEW_SESSION_ID)).thenReturn(List.of());
	}

	private void assertErrorCode(SessionErrorCode expected) {
		assertThatThrownBy(() -> service.openReview(USER_ID, REPORT_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(expected);
	}

	private static UUID eqSourceAttempt() {
		return org.mockito.ArgumentMatchers.eq(SOURCE_ATTEMPT_ID);
	}
}
