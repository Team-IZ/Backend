package com.bigproject.backend.domain.notification.presentation.dto;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 34차 R9 — 인박스 밴드. 화면이 섹션을 나누는 기준이라 경계가 흔들리면 안 된다. */
class NotificationInboxBandTest {
	private final OffsetDateTime now = OffsetDateTime.parse("2026-08-18T14:00:00Z");

	@Test
	void 마감이_지났으면_1밴드다() {
		assertThat(band("2026-08-18T13:59:59Z")).isEqualTo(1);
		assertThat(band("2026-08-17T23:59:00Z")).isEqualTo(1);
	}

	/** 지금과 같은 시각은 이미 마감이다 — 「남아 있다」고 말할 수 없다. */
	@Test
	void 마감이_지금이면_1밴드다() {
		assertThat(band("2026-08-18T14:00:00Z")).isEqualTo(1);
	}

	@Test
	void 오늘_안에_마감이면_2밴드다() {
		assertThat(band("2026-08-18T14:00:01Z")).isEqualTo(2);
		assertThat(band("2026-08-18T23:59:00Z")).isEqualTo(2);
	}

	/**
	 * 경계는 절대 24시간이 아니라 날짜다. 내일 오전 9시는 22시간 뒤지만 오늘이 아니라 3밴드다 —
	 * 매니저는 하루를 단위로 일하므로 언제 화면을 열었는지에 따라 밴드가 달라지면 안 된다.
	 */
	@Test
	void 날짜가_바뀌면_24시간_안이어도_3밴드다() {
		assertThat(band("2026-08-19T09:00:00Z")).isEqualTo(3);
		assertThat(band("2026-08-25T09:00:00Z")).isEqualTo(3);
	}

	@Test
	void 마감이_없으면_4밴드다() {
		assertThat(NotificationInboxResponse.InboxItem.bandOf(null, now)).isEqualTo(4);
	}

	private int band(String deadline) {
		return NotificationInboxResponse.InboxItem.bandOf(OffsetDateTime.parse(deadline), now);
	}
}
