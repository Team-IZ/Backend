package com.bigproject.backend.domain.analytics.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 같은 회차의 기수 전체 위험 비율과 견준 반·팀 위험 비율의 방향.
 *
 * 임계 구간을 두지 않고 단순 비교한다. 기수보다 높으면 나쁨, 낮으면 좋음, 같으면 같음이다.
 * 검증 개념 비교(ChangeDirection)와 달리 완충 구간이 없으므로 별도 열거형으로 둔다.
 *
 * 클라이언트가 다시 계산하지 않도록 서버가 판정해 내려준다.
 * CohortComparisonResponse가 levelBand·changeThreshold를 서버 계산으로 내려주는 것과 같은 규약이다.
 */
@Schema(name = "CohortRiskComparison",
		description = "같은 회차의 기수 전체 위험 비율과 견준 방향. BETTER(낮음) · SAME(같음) · WORSE(높음). 임계 구간 없이 단순 비교합니다.",
		enumAsRef = true)
public enum CohortRiskComparison {
	// 기수 전체보다 위험 비율이 낮음
	BETTER,
	// 기수 전체와 위험 비율이 같음
	SAME,
	// 기수 전체보다 위험 비율이 높음
	WORSE
}
