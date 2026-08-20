package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
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
 * {@code PENDING_PUBLISH}("응시 완료")가 실제로는 응시가 안 끝났거나 끝날 수 없는 회차까지
 * 삼키던 버그 두 건을 고정한다.
 *
 * <p><b>2026-08-20</b> — 응시가 아직 {@code COMPLETED}에 이르지 못한 회차. 실사용 재현: 코드
 * 분석이 진행 중인 회차(측정 응시 상태 {@code SESSION_READY})가 "내 리포트" 좌측 레일에
 * "응시 완료"로 떴다 — 이해도 확인은 시작도 안 했는데 "다 봤고 리포트만 기다리는 중"으로
 * 읽혔다. 원인은 상태 분기 ①②③이 {@code NOT_SUBMITTED}·{@code NOT_ATTENDED}·
 * {@code SESSION_INCOMPLETE} 세 종료 사유만 걸렀고, 그 외의 <b>진행 중(비종료)</b> 응시 상태
 * ({@code NOT_STARTED}·{@code SUBMITTED}·{@code ANALYZING}·{@code SESSION_READY}·
 * {@code SESSION_IN_PROGRESS})는 아무 데도 안 걸려 그대로 ④(발행 전=PENDING_PUBLISH)로
 * 떨어졌던 것이다.
 *
 * <p><b>2026-08-21</b> — 코드 분석이 실패로 끝났고 리포트 행이 아예 없는 회차. 실사용 재현:
 * 문주안 계정 미니프로젝트 3차가 "리포트를 만들고 있어요 · 발행 예정 N월 N일 이후"로 떴는데
 * 그 발행 예정일은 한 달 전에 지나 있었다 — 분석 실패는 리포트를 만들 근거 자체가 없는데
 * 이 사실을 거르는 분기가 없어 똑같이 ④로 떨어졌던 것이다.
 */
class InProgressRoundStatusTest {

	private static final int REVIEW_WINDOW_DAYS = 3;
	private static final UUID USER = UUID.randomUUID();
	private static final UUID ROUND = UUID.randomUUID();

	private TraineeReportQueryRepository queryRepository;
	private TraineeReportServiceImpl service;

	@BeforeEach
	void setUp() {
		queryRepository = mock(TraineeReportQueryRepository.class);
		service = new TraineeReportServiceImpl(queryRepository, new ObjectMapper(), REVIEW_WINDOW_DAYS);
	}

	@Test
	@DisplayName("코드 분석이 진행 중(SESSION_READY)이면 IN_PROGRESS다 — PENDING_PUBLISH가 아니다")
	void codeAnalysisInProgressIsNotMistakenForAttemptCompleted() {
		assertThat(statusOf(inProgress("SESSION_READY"))).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("이해도 확인 세션이 진행 중이어도 IN_PROGRESS다")
	void sessionInProgressIsNotMistakenForAttemptCompleted() {
		assertThat(statusOf(inProgress("SESSION_IN_PROGRESS"))).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("코드 제출 전(NOT_STARTED)에 응시 행이 이미 있어도 IN_PROGRESS다")
	void notYetSubmittedIsInProgressNotPendingPublish() {
		assertThat(statusOf(inProgress("NOT_STARTED"))).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("코드 제출됨(SUBMITTED)·분석 중(ANALYZING)도 IN_PROGRESS다")
	void submittedAndAnalyzingAreInProgress() {
		assertThat(statusOf(inProgress("SUBMITTED"))).isEqualTo("IN_PROGRESS");
		assertThat(statusOf(inProgress("ANALYZING"))).isEqualTo("IN_PROGRESS");
	}

	/** 실제로 완료됐고 리포트만 아직 없으면 여전히 PENDING_PUBLISH다 — 회귀 방지. */
	@Test
	@DisplayName("COMPLETED인데 리포트가 아직 없으면 여전히 PENDING_PUBLISH다")
	void completedWithoutAReportYetIsStillPendingPublish() {
		RoundRow row = new RoundRow(
				ROUND, "미니프로젝트 6차 이해도 확인", 1, "미니프로젝트 6차",
				null, null, null, 0, 0,
				UUID.randomUUID(), "COMPLETED", null, "NOT_REQUIRED",
				Instant.now().minus(Duration.ofDays(1)), null, null,
				null, null, null);

		assertThat(statusOf(row)).isEqualTo("PENDING_PUBLISH");
	}

	/**
	 * 분석 실패(FAILED)여도 이미 리포트가 발행됐으면 PUBLISHED다 — IN_PROGRESS가 FAILED·EXPIRED를
	 * 가로채면 안 된다는 회귀 방지(실제로 이런 회차가 있다: 분석 실패해도 발행된 리포트가 존재).
	 *
	 * <p>이 테스트는 {@code ANALYSIS_FAILED} 상태(2026-08-21 추가)의 회귀 가드도 겸한다 — 이 회차는
	 * {@code reportId}가 있으므로 새 분기(③-3)를 타지 않고 그대로 ⑤(PUBLISHED)로 떨어져야 한다.
	 */
	@Test
	@DisplayName("FAILED여도 이미 발행됐으면 PUBLISHED다 — IN_PROGRESS·ANALYSIS_FAILED가 가로채지 않는다")
	void failedButAlreadyPublishedIsStillPublishedNotInProgress() {
		RoundRow row = new RoundRow(
				ROUND, "미니프로젝트 4차 이해도 확인", 1, "미니프로젝트 4차",
				UUID.randomUUID(), null, null, 0, 0,
				UUID.randomUUID(), "FAILED", "ANALYSIS_FAILED", "NOT_REQUIRED",
				Instant.now().minus(Duration.ofDays(10)), null, Instant.now().minus(Duration.ofDays(5)),
				null, null, null);

		assertThat(statusOf(row)).isEqualTo("PUBLISHED");
	}

	/**
	 * 2026-08-21 발견·수정 — 분석 실패로 끝났고 리포트 행이 아예 없으면 ANALYSIS_FAILED다.
	 *
	 * <p>실사용 재현: 문주안 계정 미니프로젝트 3차. 코드 분석이 실패해 이해도 확인 문항 자체가
	 * 없는데 이 사실을 거르는 분기가 없어 PENDING_PUBLISH("응시 완료" · "리포트를 만들고
	 * 있어요 · 발행 예정 N월 N일 이후")로 떨어졌다 — 그 발행 예정일은 이미 한 달 전에 지났다.
	 */
	@Test
	@DisplayName("분석 실패(ANALYSIS_FAILED)이고 리포트가 없으면 ANALYSIS_FAILED다 — PENDING_PUBLISH가 아니다")
	void analysisFailedWithoutAReportIsAnalysisFailedNotPendingPublish() {
		RoundRow row = new RoundRow(
				ROUND, "미니프로젝트 3차 이해도 확인", 1, "미니프로젝트 3차",
				null, null, null, 0, 0,
				UUID.randomUUID(), "FAILED", "ANALYSIS_FAILED", "NOT_REQUIRED",
				Instant.now().minus(Duration.ofDays(40)), Instant.now().minus(Duration.ofDays(30)), null,
				null, null, null);

		assertThat(statusOf(row)).isEqualTo("ANALYSIS_FAILED");
	}

	// ------------------------------------------------------------------ fixture

	private String statusOf(RoundRow row) {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(row));
		return service.findMyReports(USER).reportsById().get(ROUND.toString()).status();
	}

	/** 응시 행은 있지만 attemptStatus가 아직 COMPLETED에 이르지 못한 회차. 리포트도 스냅샷도 없다. */
	private RoundRow inProgress(String attemptStatus) {
		return new RoundRow(
				ROUND, "미니프로젝트 6차 이해도 확인", 1, "미니프로젝트 6차",
				null, null, null, 0, 0,
				UUID.randomUUID(), attemptStatus, null, "NOT_REQUIRED",
				Instant.now().plus(Duration.ofDays(1)), null, null,
				null, null, null);
	}
}
