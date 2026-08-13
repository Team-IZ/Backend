package com.bigproject.backend.domain.projectexecution.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 22차 R5 ① — 생성에서 마감 시각을 <b>생략했을 때</b>의 파생 규칙.
 *
 * <p>{@code submission_due_at}이 DB에서 NOT NULL이라 비워 둘 수 없다. 그래서 종료일의
 * <b>23:59 KST</b>로 파생하는데, 이 값이 프론트가 이미 그렇게 읽고 있는 규칙이라
 * 숫자 하나가 틀리면 화면이 하루를 밀어 그린다.
 */
class SubmissionDueAtDerivationTest {

	/** {@code 23:59 KST}는 같은 날 {@code 14:59Z}다 — KST가 UTC+9라 9시간을 뺀다. */
	@Test
	void derivesTheEndOfTheEndDateInKoreanTime() {
		Instant due = ProjectServiceImpl.deriveSubmissionDueAt(LocalDate.of(2026, 8, 21));

		assertThat(due).isEqualTo(Instant.parse("2026-08-21T14:59:00Z"));
	}

	/**
	 * 한국은 서머타임이 없어 연중 어느 날이든 UTC+9다. 고정 오프셋을 쓰지 않고 지역 시간대로
	 * 계산하는 것은 규칙을 「한국의 23:59」로 적어 두기 위해서다 — 오프셋을 박아 두면
	 * 나중에 다른 시간대를 지원할 때 그것이 규칙인지 우연인지 알 수 없다.
	 */
	@Test
	void staysAtNineHoursOffsetThroughTheYear() {
		assertThat(ProjectServiceImpl.deriveSubmissionDueAt(LocalDate.of(2026, 1, 15)))
				.isEqualTo(Instant.parse("2026-01-15T14:59:00Z"));
		assertThat(ProjectServiceImpl.deriveSubmissionDueAt(LocalDate.of(2026, 7, 15)))
				.isEqualTo(Instant.parse("2026-07-15T14:59:00Z"));
	}

	/** 마감은 그날이 끝나기 <b>1분 전</b>이다. 자정으로 올리면 다음 날 제출이 통과한다. */
	@Test
	void landsOneMinuteBeforeMidnightRatherThanOnIt() {
		Instant due = ProjectServiceImpl.deriveSubmissionDueAt(LocalDate.of(2026, 8, 21));
		Instant midnightAfter = LocalDate.of(2026, 8, 22)
				.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();

		assertThat(due).isBefore(midnightAfter);
		assertThat(java.time.Duration.between(due, midnightAfter)).isEqualTo(java.time.Duration.ofMinutes(1));
	}
}
