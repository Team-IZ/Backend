package com.bigproject.backend.domain.analytics.domain;

/**
 * 두 기수의 평균 도달 단계를 뺄 수 없는 이유.
 * 화면이 '비교 대상 아님' 칸에 붙이는 설명 문구를 고르는 데 쓴다.
 */
public enum NotComparableReason {
	// 지난 기수에 없던 개념 (목업의 '6기에 없던 개념')
	ABSENT_IN_BASELINE,
	// 이번 기수에 없는 개념
	ABSENT_IN_TARGET,
	// 한쪽에서 teaches.status='MERGED'라 두 기수의 개념이 같은 뜻이 아님
	CONCEPT_MERGED,
	// 반복 개념 집계 산식이 확정되지 않아(POLICY_REQUIRED) 또는 집계 실패(FAILED)로 평균을 신뢰할 수 없음
	AGGREGATION_UNAVAILABLE
}
