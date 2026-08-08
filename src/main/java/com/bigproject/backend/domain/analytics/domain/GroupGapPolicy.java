package com.bigproject.backend.domain.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 집단 미달 판정 정책.
 *
 * 반 인원의 절반을 넘는 인원이 한 검증 개념에서 2단 이하면 개인 문제가 아니라 반 문제로 본다.
 * 화면이 "절반을 넘어 반 문제로 판정 — 개인 위험 사유에서 빠집니다"로 설명하는 기준이다.
 *
 * 2단 이하만 세고 미응시·무효 확정은 분자에서 뺀다. 정의서가 미응시를 0단으로 치환하지 말라고
 * 반복 선언하므로 응시하지 않은 인원을 저성과로 계상하지 않는다. 분모는 반 인원 전체라서
 * 그 인원은 분모에만 남는다.
 */
public final class GroupGapPolicy {
	// 반 인원 대비 2단 이하 비율이 이 값을 초과해야 집단 미달이다. 같으면 미달이 아니다.
	public static final BigDecimal UNDERPERFORMANCE_THRESHOLD_RATIO = new BigDecimal("0.5");
	public static final int LOW_LEVEL_MAX = 2;
	private static final int RATE_SCALE = 4;

	private GroupGapPolicy() {
	}

	public static boolean underperforming(long lowLevelCount, long classMemberCount) {
		BigDecimal rate = lowLevelRate(lowLevelCount, classMemberCount);
		return rate != null && rate.compareTo(UNDERPERFORMANCE_THRESHOLD_RATIO) > 0;
	}

	/**
	 * 분모가 0이면 0%가 아니라 값 없음이다. 대상자가 없는 반과 아무도 미달하지 않은 반을 구분한다.
	 */
	public static BigDecimal lowLevelRate(long lowLevelCount, long classMemberCount) {
		if (classMemberCount <= 0) {
			return null;
		}
		return BigDecimal.valueOf(lowLevelCount)
				.divide(BigDecimal.valueOf(classMemberCount), RATE_SCALE, RoundingMode.HALF_UP);
	}
}
