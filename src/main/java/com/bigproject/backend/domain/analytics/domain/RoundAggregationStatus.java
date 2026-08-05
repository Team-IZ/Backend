package com.bigproject.backend.domain.analytics.domain;

/**
 * 회차 열의 집계 가능 상태.
 * project_assessment_round.status(PLANNED / OPEN / CLOSED / COMPLETED)를 화면 표기 단위로 축약한다.
 * 값이 아직 없는 상태(시작 전·집계 전)와 위험자 0%를 서로 합치지 않기 위해 분리한다.
 */
public enum RoundAggregationStatus {
	// 회차가 아직 시작되지 않아 표시할 값 자체가 없음 (PLANNED)
	NOT_STARTED,
	// 회차는 진행됐지만 결과가 확정되지 않아 집계 전 (OPEN, CLOSED)
	NOT_AGGREGATED,
	// 회차 결과가 확정되어 비율을 읽을 수 있음 (COMPLETED)
	AGGREGATED;

	public static RoundAggregationStatus from(String roundStatus) {
		return switch (roundStatus) {
			case "PLANNED" -> NOT_STARTED;
			case "OPEN", "CLOSED" -> NOT_AGGREGATED;
			case "COMPLETED" -> AGGREGATED;
			default -> throw new IllegalStateException("지원하지 않는 회차 상태입니다: " + roundStatus);
		};
	}

	public boolean readable() {
		return this == AGGREGATED;
	}
}
