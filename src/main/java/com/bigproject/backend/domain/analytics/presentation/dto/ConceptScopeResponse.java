package com.bigproject.backend.domain.analytics.presentation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 면담 브리프의 개념 소관 판정이다.
 *
 * <p>분모인 {@code validRespondentCount}는 <b>유효 응시자</b>이며 반 명부 인원이 아니다 —
 * 미응시·무효 확정·중단은 뺀다. 히트맵의 집단 미달과 <b>같은 모집단·같은 임계</b>를 쓰므로
 * 두 화면이 같은 개념에 다른 답을 내지 않는다.
 */
public record ConceptScopeResponse(
		UUID cohortId, UUID assessmentRoundId, UUID classroomId, UUID teachesId,
		String conceptName, long lowLevelCount, long validRespondentCount,
		BigDecimal lowLevelRate, Scope scope, int policyVersion, OffsetDateTime calculatedAt) {
	public enum Scope { INDIVIDUAL, CLASS_WIDE }
}
