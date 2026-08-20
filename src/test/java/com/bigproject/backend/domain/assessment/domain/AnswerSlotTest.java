package com.bigproject.backend.domain.assessment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerSlotTest {

	@Test
	void 힌트2의_바로_앞_슬롯은_힌트1이다() {
		assertThat(AnswerSlot.SECOND_HINT.previous()).isEqualTo(AnswerSlot.FIRST_HINT);
	}

	@Test
	void 힌트1의_바로_앞_슬롯은_질문이다() {
		assertThat(AnswerSlot.FIRST_HINT.previous()).isEqualTo(AnswerSlot.QUESTION);
	}

	@Test
	void 질문에는_앞_슬롯이_없다() {
		assertThatThrownBy(AnswerSlot.QUESTION::previous).isInstanceOf(IllegalStateException.class);
	}
}
