package com.bigproject.backend.domain.analytics.domain;

/**
 * 지난 기수 대비 이번 기수의 평균 도달 단계 변화 방향.
 * 경계값은 좋아짐·나빠짐 대칭으로 ±0.3단이며 {@link CohortComparisonPolicy}가 단일 지점이다.
 */
public enum ChangeDirection {
	// delta <= -0.3단
	WORSE,
	// -0.3단 < delta < +0.3단
	SIMILAR,
	// delta >= +0.3단
	BETTER,
	// 한쪽 기수에 개념이 없거나 병합됐거나 집계 산식이 확정되지 않아 뺄셈 자체가 성립하지 않음
	NOT_COMPARABLE
}
