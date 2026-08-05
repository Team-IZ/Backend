package com.bigproject.backend.domain.analytics.domain;

/**
 * 기수 간 비교 행 정렬 기준.
 * NOT_COMPARABLE 행은 delta가 없어 어떤 기준에서도 마지막에 놓고 그 안에서는 CONCEPT 순서를 따른다.
 */
public enum ComparisonSort {
	// 나빠진 순. delta 오름차순이라 가장 많이 떨어진 개념이 먼저 온다.
	WORSENED,
	// 좋아진 순. delta 내림차순.
	IMPROVED,
	// 검증 개념 순. 교안의 장 순서 → 시작 쪽수 → 개념명.
	CONCEPT
}
