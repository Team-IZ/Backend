package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 교육생 홈 3구획을 한 응답으로 묶는다. 홈이 한 화면에 세 구획을 그리므로 요청을 나누지 않는다.
 *
 * <p>구획마다 클라이언트 표시 방식이 달라 필드 집합을 공유하지 않는다 —
 * current 35 · upcoming 7 · past 8.
 */
@Schema(description = "교육생 홈 3구획")
public record AssessmentRoundsResponse(
		@Schema(description = "객체 자체는 항상 존재한다") MembershipResponse membership,
		@Schema(description = "객체 자체는 항상 존재한다. 진행 회차가 없으면 NO_ACTIVE_ROUND 합성 카드")
		CurrentRoundResponse current,
		@Schema(description = "배열 자체는 항상 존재한다. 없으면 빈 배열") List<UpcomingRoundResponse> upcoming,
		@Schema(description = "배열 자체는 항상 존재한다. 없으면 빈 배열") List<PastRoundResponse> past
) {
}
