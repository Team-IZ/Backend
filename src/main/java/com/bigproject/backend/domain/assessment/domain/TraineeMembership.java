package com.bigproject.backend.domain.assessment.domain;

import java.util.UUID;

/**
 * 기수 스코프 소속. 진행 중인 회차가 없어 {@code trainee_home_round_view}가 0행일 때
 * {@code cohort_member} + {@code class_membership}에서 직접 조회하는 대체 원천이다.
 *
 * <p>팀은 여기 없다. 팀은 프로젝트마다 새로 편성되는 회차 스코프 값이라
 * 회차 카드({@code current})가 소유한다.
 */
public record TraineeMembership(
		UUID cohortId,
		String cohortName,
		UUID classId,
		String className
) {
	public static TraineeMembership empty() {
		return new TraineeMembership(null, null, null, null);
	}
}
