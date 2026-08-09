package com.bigproject.backend.domain.analytics.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 격자를 그릴 수 없는 상태의 원인.
 * 집계 전(회차 흐름)과 달리 여기서는 '비교 대상 부재'가 원인이라 별도 코드로 구분한다.
 */
@Schema(name = "ComparisonEmptyState",
		description = "기수 간 비교 격자를 그릴 수 없는 원인. NO_COMPARABLE_COHORT(같은 기관에 다른 기수가 없음) · "
				+ "REPORT_NOT_PUBLISHED(한쪽 기수에 발행된 리포트가 없음) · NO_SHARED_CONCEPT(공통 검증 개념이 0건)",
		enumAsRef = true)
public enum ComparisonEmptyState {
	// 같은 기관에 다른 기수가 없음. 목업의 '7기가 이 기관의 첫 기수예요'
	NO_COMPARABLE_COHORT,
	// 한쪽 기수에 발행된 수업 진단 리포트가 없어 읽을 스냅샷이 없음
	REPORT_NOT_PUBLISHED,
	// 두 기수에 공통으로 있는 검증 개념이 0건
	NO_SHARED_CONCEPT
}
