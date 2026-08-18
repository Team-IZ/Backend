package com.bigproject.backend.domain.intervention.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 32차 R4 — 실서버에서 관측된 문장을 그대로 넣어 본다. */
class RiskSummaryTextTest {

	@Test
	void 실서버에_남아_있던_태그를_걷어낸다() {
		assertThat(RiskSummaryText.stripSeverityTag(
				"[SEVERE] 최근 2개 유효 회차 점수 1.67 → 1.67 (기수 평균 대비 저점)"))
				.isEqualTo("최근 2개 유효 회차 점수 1.67 → 1.67 (기수 평균 대비 저점)");

		assertThat(RiskSummaryText.stripSeverityTag("[WARN] 응시 기간 종료까지 세션을 시작하지 않음"))
				.isEqualTo("응시 기간 종료까지 세션을 시작하지 않음");
	}

	/** 34차 R5 — 앞의 둘이 사라지자 드러난 세 번째. 시드 원장에 34건 있었다. */
	@Test
	void RISK_태그도_걷어낸다() {
		assertThat(RiskSummaryText.stripSeverityTag("[RISK] 정책·무결성 기준으로 결과가 무효 처리됨"))
				.isEqualTo("정책·무결성 기준으로 결과가 무효 처리됨");

		assertThat(RiskSummaryText.stripSeverityTag("[RISK] 최근 3개 회차 연속 하락 3.67 → 2.67 → 2.00"))
				.isEqualTo("최근 3개 회차 연속 하락 3.67 → 2.67 → 2.00");
	}

	/** 지금 생성 코드가 만드는 문장. 태그가 없으므로 아무 일도 일어나면 안 된다. */
	@Test
	void 태그가_없으면_그대로_둔다() {
		String current = "평균 도달 단계 2.33 → 1.67. 2단 미만 2개.";
		assertThat(RiskSummaryText.stripSeverityTag(current)).isEqualTo(current);
	}

	/**
	 * 맨 앞 하나만 본다. 판정 근거가 값을 대괄호로 인용하는 형식을 나중에 쓸 수 있고,
	 * 그때 이 함수가 뜻을 지우면 안 된다.
	 */
	@Test
	void 문장_중간의_대괄호는_건드리지_않는다() {
		assertThat(RiskSummaryText.stripSeverityTag("[WARN] 개념 [트랜잭션 경계]에서 미달"))
				.isEqualTo("개념 [트랜잭션 경계]에서 미달");
	}

	/** UPDATE가 돈 뒤에도 안전해야 한다 — 여러 번 적용해도 결과가 같다. */
	@Test
	void 여러_번_적용해도_같다() {
		String once = RiskSummaryText.stripSeverityTag("[SEVERE] 저점");
		assertThat(RiskSummaryText.stripSeverityTag(once)).isEqualTo(once);
	}

	@Test
	void null은_그대로_돌려준다() {
		assertThat(RiskSummaryText.stripSeverityTag(null)).isNull();
	}
}
