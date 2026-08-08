package com.bigproject.backend.domain.analytics.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회차 열의 집계 가능 상태.
 * 값이 아직 없는 상태(시작 전·집계 전)와 위험자 0%를 서로 합치지 않기 위해 분리한다.
 *
 * 판정 기준은 회차 생명주기가 아니라 <b>리포트 발행 여부</b>다.
 * 화면이 "집계 전 · 리포트 미발행 — 발행되면 값이 채워집니다"로 설명하므로 그 계약에 맞춘다.
 * project_assessment_round.status는 시작 전(PLANNED)을 가려내는 데만 쓴다.
 */
@Schema(name = "RoundAggregationStatus",
		description = "회차 열의 집계 가능 상태. NOT_STARTED(시작 전이라 표시할 값 없음) · "
				+ "NOT_AGGREGATED(진행됐으나 발행된 리포트 없음) · AGGREGATED(발행 완료, 비율을 읽을 수 있음)",
		enumAsRef = true)
public enum RoundAggregationStatus {
	// 회차가 아직 시작되지 않아 표시할 값 자체가 없음 (PLANNED)
	NOT_STARTED,
	// 회차는 진행됐지만 발행된 리포트가 없어 집계 전
	NOT_AGGREGATED,
	// 발행된 리포트가 있어 비율을 읽을 수 있음
	AGGREGATED;

	public static RoundAggregationStatus from(String roundStatus, boolean reportPublished) {
		return switch (roundStatus) {
			case "PLANNED" -> NOT_STARTED;
			case "OPEN", "CLOSED", "COMPLETED" -> reportPublished ? AGGREGATED : NOT_AGGREGATED;
			default -> throw new IllegalStateException("지원하지 않는 회차 상태입니다: " + roundStatus);
		};
	}

	public boolean readable() {
		return this == AGGREGATED;
	}
}
