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
 * 응시 기록이 없는 회차가 <b>"아직"인지 "놓쳤는지"</b>를 가른다.
 *
 * <p>한 값으로 뭉쳐 두면 화면이 두 상황에 같은 문구를 쓴다. 그러면 **정말 놓친 학생에게서 경고가
 * 사라지거나**(*"사정이 있었다면 매니저에게 알려 주세요"*), 아직 시간이 있는 학생에게 놓쳤다고
 * 말하게 된다(26차 A1).
 *
 * <p>가르는 축은 제출 마감이며, 홈이 {@code SUBMISSION_REQUIRED}와 {@code SUBMISSION_MISSED}를
 * 가를 때 쓰는 값과 같다 — 같은 회차를 두 화면이 다르게 말하던 것이 요청의 발단이었다.
 */
class MissedRoundStatusTest {

	/** 운영 기본값과 같은 다시 보기 창(일). 발행일로부터 이만큼 지나면 잠금이 풀린다. */
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
	@DisplayName("마감 전이고 응시 기록이 없으면 NOT_STARTED — 아직 낼 수 있다")
	void beforeTheDeadlineTheRoundIsNotStartedYet() {
		assertThat(statusOf(noAttempt(Instant.now().plus(Duration.ofDays(8)), null)))
				.isEqualTo("NOT_STARTED");
	}

	@Test
	@DisplayName("마감이 지났고 응시 기록이 없으면 NOT_ATTEMPTED — 놓쳤다")
	void afterTheDeadlineTheRoundIsMissed() {
		assertThat(statusOf(noAttempt(Instant.now().minus(Duration.ofDays(1)), null)))
				.isEqualTo("NOT_ATTEMPTED");
	}

	/**
	 * 종료 사유가 남았다는 것은 그 회차가 이미 닫혔다는 뜻이다. 마감이 미래여도(운영자가 회차 일정을
	 * 뒤로 옮긴 경우) 기회는 돌아오지 않으므로 "아직"이라고 말하면 안 된다.
	 */
	@Test
	@DisplayName("응시가 미제출로 끝났으면 마감이 남아 있어도 NOT_ATTEMPTED")
	void aTerminatedAttemptIsMissedEvenIfTheDeadlineMoved() {
		assertThat(statusOf(noAttempt(Instant.now().plus(Duration.ofDays(8)), "NOT_SUBMITTED")))
				.isEqualTo("NOT_ATTEMPTED");
	}

	/** 마감을 모르면 "아직 시간이 있다"고 말할 근거가 없다. */
	@Test
	@DisplayName("제출 마감이 없는 회차는 NOT_ATTEMPTED로 둔다")
	void withoutADeadlineTheRoundIsNotClaimedToBeOpen() {
		assertThat(statusOf(noAttempt(null, null))).isEqualTo("NOT_ATTEMPTED");
	}

	// ------------------------------------------------------------------ fixture

	private String statusOf(RoundRow row) {
		when(queryRepository.findRounds(USER)).thenReturn(List.of(row));
		return service.findMyReports(USER).reportsById().get(ROUND.toString()).status();
	}

	/** 응시 행이 없거나 종료 사유만 남은 회차. 리포트도 스냅샷도 없다. */
	private RoundRow noAttempt(Instant submissionDueAt, String terminalReasonCode) {
		UUID attemptId = terminalReasonCode == null ? null : UUID.randomUUID();
		return new RoundRow(
				ROUND, "미니프로젝트 6차 이해도 확인", 1, "미니프로젝트 6차",
				null, null, null, 0, 0,
				attemptId, terminalReasonCode == null ? null : "COMPLETED", terminalReasonCode,
				"NOT_REQUIRED",
				submissionDueAt, null, null,
				null, null, null);
	}
}
