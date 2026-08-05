package com.bigproject.backend.domain.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * 기수 간 비교의 눈금·경계값 단일 지점.
 *
 * 색 눈금은 회차 흐름(위험 비율)과 달리 절대 눈금이며 MG-02 히트맵과 같은 값이어야 하므로
 * 클라이언트가 각자 계산하지 않도록 서버가 임계치와 밴드 경계를 함께 내려준다.
 */
public final class CohortComparisonPolicy {

	/** 도달 단계 최소값. 1단도 통과하지 못한 응시가 존재하므로 0을 포함한다. */
	public static final int MIN_LEVEL = 0;
	public static final int MAX_LEVEL = 4;

	/**
	 * 색 밴드 경계. 0~4 정수 5개에 5색을 대응시키되 평균은 연속값이므로
	 * 각 정수를 중심으로 폭 1(양 끝은 0.5)의 구간을 두고 반올림으로 배정한다.
	 * band = round(value)와 같으며 경계값은 위쪽 밴드로 올린다.
	 */
	public static final List<BigDecimal> BAND_THRESHOLDS = List.of(
			new BigDecimal("0.5"),
			new BigDecimal("1.5"),
			new BigDecimal("2.5"),
			new BigDecimal("3.5")
	);

	/** 나빠짐 경계. 좋아짐과 대칭이다. */
	public static final BigDecimal WORSENED_THRESHOLD = new BigDecimal("-0.3");
	public static final BigDecimal IMPROVED_THRESHOLD = new BigDecimal("0.3");

	/**
	 * 평균 도달 단계와 변화량의 소수 자리.
	 * delta를 반올림한 평균끼리 빼서 구하므로 화면에 보이는 두 값의 차이와 delta가 항상 일치한다.
	 */
	public static final int LEVEL_SCALE = 2;

	/**
	 * 평균을 신뢰할 수 없게 만드는 집계 상태.
	 * POLICY_REQUIRED는 한 개념이 여러 회차에 반복 등장할 때의 산식이 아직 확정되지 않은 상태이고
	 * FAILED는 집계 자체가 실패한 상태라 두 경우 모두 비교에서 뺀다.
	 */
	public static final Set<String> BLOCKING_AGGREGATION_STATUSES = Set.of("POLICY_REQUIRED", "FAILED");

	private CohortComparisonPolicy() {
	}

	/**
	 * 평균 도달 단계가 속한 색 밴드(0~4)를 돌려준다.
	 * 값이 없으면 밴드도 없다. 0단과 값 없음은 서로 다른 칸이기 때문이다.
	 */
	public static Integer bandOf(BigDecimal averageLevel) {
		if (averageLevel == null) {
			return null;
		}
		int band = averageLevel.setScale(0, RoundingMode.HALF_UP).intValue();
		return Math.min(MAX_LEVEL, Math.max(MIN_LEVEL, band));
	}

	public static ChangeDirection directionOf(BigDecimal delta) {
		if (delta == null) {
			return ChangeDirection.NOT_COMPARABLE;
		}
		if (delta.compareTo(WORSENED_THRESHOLD) <= 0) {
			return ChangeDirection.WORSE;
		}
		if (delta.compareTo(IMPROVED_THRESHOLD) >= 0) {
			return ChangeDirection.BETTER;
		}
		return ChangeDirection.SIMILAR;
	}

	public static boolean blocksComparison(String aggregationStatus) {
		return aggregationStatus != null && BLOCKING_AGGREGATION_STATUSES.contains(aggregationStatus);
	}
}
