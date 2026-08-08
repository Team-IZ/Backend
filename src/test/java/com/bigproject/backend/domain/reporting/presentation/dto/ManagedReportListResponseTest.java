package com.bigproject.backend.domain.reporting.presentation.dto;

import com.bigproject.backend.domain.reporting.domain.ManagedReportQueryRepository.ManagedReportRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매니저 목록의 {@code bodyVisible} 판정. <b>발행과 공개는 다른 사건</b>이라
 * 둘 중 하나만으로는 열리지 않는다는 것을 고정한다.
 */
class ManagedReportListResponseTest {

	private static final Instant PUBLISHED = Instant.parse("2026-07-15T00:00:00Z");

	@Test
	void 발행되고_공개까지_됐을_때만_본문이_보인다() {
		ManagedReportListResponse response = ManagedReportListResponse.from(List.of(
				row("ACTIVE", PUBLISHED, "RELEASED", "SUMMARY")));

		assertThat(response.reports().get(0).bodyVisible()).isTrue();
	}

	@Test
	void 발행됐지만_공개_범위가_미지정이면_안_보인다() {
		ManagedReportListResponse response = ManagedReportListResponse.from(List.of(
				row("ACTIVE", PUBLISHED, "NOT_CONFIGURED", null)));

		assertThat(response.reports().get(0).bodyVisible()).isFalse();
		// 미지정은 "비공개"가 아니다. 화면이 `공개 범위 미지정`으로 그릴 근거가 남아야 한다.
		assertThat(response.reports().get(0).releaseStatus()).isEqualTo("NOT_CONFIGURED");
		assertThat(response.reports().get(0).scope()).isNull();
	}

	@Test
	void 공개_범위를_정했어도_발행_전이면_안_보인다() {
		ManagedReportListResponse response = ManagedReportListResponse.from(List.of(
				row("ACTIVE", null, "RELEASED", "FULL")));

		assertThat(response.reports().get(0).bodyVisible()).isFalse();
	}

	@Test
	void 대체된_리포트는_보이지_않는다() {
		ManagedReportListResponse response = ManagedReportListResponse.from(List.of(
				row("SUPERSEDED", PUBLISHED, "RELEASED", "FULL")));

		assertThat(response.reports().get(0).bodyVisible()).isFalse();
	}

	@Test
	void 비공개로_확정한_경우_PRIVATE_범위가_남는다() {
		ManagedReportListResponse response = ManagedReportListResponse.from(List.of(
				row("ACTIVE", PUBLISHED, "WITHHELD", "PRIVATE")));

		assertThat(response.reports().get(0).bodyVisible()).isFalse();
		// WITHHELD는 "정해서 닫았다"라 미지정(NOT_CONFIGURED)과 구분돼야 한다.
		assertThat(response.reports().get(0).scope()).isEqualTo("PRIVATE");
	}

	private static ManagedReportRow row(String lifecycle, Instant publishedAt, String releaseStatus, String scope) {
		return new ManagedReportRow(
				UUID.randomUUID(), UUID.randomUUID(), "미프 3차", 3,
				UUID.randomUUID(), "김OO",
				UUID.randomUUID(), "A반",
				UUID.randomUUID(),
				lifecycle, publishedAt, releaseStatus, scope, null
		);
	}
}
