package com.bigproject.backend.domain.intervention.domain;

import com.bigproject.backend.domain.intervention.domain.InterviewRoundRepository.RoundMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 38차 R3 — {@code resultStatus}와 {@code publishedAt}이 서로 반대로 보인다는 관측을 검증한다.
 *
 * <p>32차 R1이 이미 두 축을 의도적으로 분리했다({@code resultStatus}는 {@code outcomeJudgedAt} 기준,
 * {@code publishedAt}은 리포트 발행 기준). 그래서 "판정은 끝났는데 발행 전"과 "발행은 됐는데 아직
 * 미판정" 두 조합 모두 정상 상태이지, 서로 모순이 아니다.
 */
class InterviewRoundRepositoryTest {

	private RoundMeta meta(Instant publishedAt, Instant outcomeJudgedAt) {
		return new RoundMeta(UUID.randomUUID(), 1, "미니프로젝트 1차", false, publishedAt, outcomeJudgedAt);
	}

	@Test
	void 판정이_끝나면_발행_여부와_무관하게_READY다() {
		assertThat(meta(null, Instant.now()).resultStatus()).isEqualTo("READY");
		assertThat(meta(Instant.now(), Instant.now()).resultStatus()).isEqualTo("READY");
	}

	@Test
	void 판정_전이면_발행_여부와_무관하게_PENDING이다() {
		assertThat(meta(null, null).resultStatus()).isEqualTo("PENDING");
		assertThat(meta(Instant.now(), null).resultStatus()).isEqualTo("PENDING");
	}

	/** 38차 R3 5차 관측 — 발행이 미뤄져도 판정이 끝났으면 READY다. 모순이 아니라 설계대로다. */
	@Test
	void 발행이_미뤄져도_판정이_끝났으면_READY다() {
		RoundMeta delayed = meta(null, Instant.parse("2026-07-20T00:00:00Z"));
		assertThat(delayed.resultStatus()).isEqualTo("READY");
		assertThat(delayed.publishedAt()).isNull();
	}

	/** 38차 R3 6차 관측 — 발행됐어도 판정이 아직이면 PENDING이다. 두 축이 독립이라 가능한 조합이다. */
	@Test
	void 발행됐어도_판정_전이면_PENDING이다() {
		RoundMeta published = meta(Instant.parse("2026-08-16T00:00:00Z"), null);
		assertThat(published.resultStatus()).isEqualTo("PENDING");
		assertThat(published.publishedAt()).isNotNull();
	}
}
