package com.bigproject.backend.domain.analytics.domain;

/**
 * 위험 교육생 비율 격자의 반·팀 행 정렬 기준.
 *
 * 네 기준 모두 이미 응답에 담긴 값으로 계산되므로 질의를 다시 하지 않고 조립된 응답을 정렬한다.
 * 격자가 반 10개 × 회차 6개 규모라 정렬 비용이 질의 왕복보다 싸다.
 */
public enum RiskTraineeSort {
	// 최근 발행 회차에서 기수 전체 비율을 얼마나 웃도는지 내림차순
	RECENT_ROUND_WORST,
	// 기수 전체보다 나쁜 회차 수 내림차순
	WORSE_ROUND_COUNT,
	// 미집계(미응시·중단·무효) 인원 내림차순
	EXCLUSION_COUNT,
	// 반·팀 이름 오름차순
	NAME
}
