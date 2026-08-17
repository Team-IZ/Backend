package com.bigproject.backend.domain.intervention.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 32차 R6 — 실제로 나갔던 문장을 그대로 넣어 본다.
 *
 * <p>정규식으로 다듬는 처리라 <b>모르는 형태가 오면 손대지 않고 남기는 것</b>이 계약이다.
 * 그 성질까지 함께 고정한다 — 문장을 잘라내면 남는 말이 뜻을 잃는다.
 */
class BriefRationaleTextTest {

	@Test
	void 문항_출처_문장에서_내부_식별자를_걷어내고_축을_단계_이름으로_바꾼다() {
		String actual = BriefRationaleText.humanize(
				"문제 1 L3 인터뷰 (interviewSourceId 7d017b49-1095-49dd-a8c7-c8d135eb78ab) 기반 Q&A 질문");

		assertThat(actual).isEqualTo("1번 문항 대안 비교 인터뷰 기반 Q&A 질문");
		assertThat(actual).doesNotContain("interviewSourceId", "L3", "7d017b49");
	}

	@Test
	void 위험_사유_코드를_배지와_같은_말로_바꾼다() {
		assertThat(BriefRationaleText.humanize("PERSISTENT_LOW 위험 사유에 기반한 확인 질문"))
				.isEqualTo("지속 저점 위험 사유에 기반한 확인 질문");

		assertThat(BriefRationaleText.humanize("STAGE_DECLINE 판정을 근거로"))
				.isEqualTo("단계 하락 판정을 근거로");
	}

	/** 식별자를 덜어낸 자리에 빈 괄호나 두 칸 공백이 남으면 안 된다. */
	@Test
	void 덜어낸_자리를_정리한다() {
		assertThat(BriefRationaleText.humanize("답변(interviewSourceId: 7d017b49-1095-49dd-a8c7-c8d135eb78ab)을 근거로"))
				.isEqualTo("답변을 근거로");

		assertThat(BriefRationaleText.humanize("근거   여러   칸")).isEqualTo("근거 여러 칸");
	}

	/**
	 * 모르는 형태는 그대로 둔다. 이 처리는 AI 문장 구조에 기대므로, 못 알아본 것을 지우면
	 * 매니저가 읽을 근거가 통째로 사라진다 — 치환이지 잘라내기가 아니다.
	 */
	@Test
	void 모르는_형태는_손대지_않는다() {
		String unknown = "지난 회차 면담에서 정한 계획을 확인하는 질문";
		assertThat(BriefRationaleText.humanize(unknown)).isEqualTo(unknown);
	}

	@Test
	void 빈_값과_null은_그대로_돌려준다() {
		assertThat(BriefRationaleText.humanize(null)).isNull();
		assertThat(BriefRationaleText.humanize("")).isEmpty();
	}

	/** 축 코드가 낱말로 서 있을 때만 바꾼다 — 다른 뜻으로 쓰인 문자열을 건드리면 안 된다. */
	@Test
	void 낱말이_아닌_축_코드는_바꾸지_않는다() {
		assertThat(BriefRationaleText.humanize("HTTP1L3X 로그")).isEqualTo("HTTP1L3X 로그");
	}
}
