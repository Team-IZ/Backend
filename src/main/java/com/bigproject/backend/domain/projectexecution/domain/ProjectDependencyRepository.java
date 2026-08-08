package com.bigproject.backend.domain.projectexecution.domain;

import java.util.UUID;

/**
 * 회차를 지워도 되는지, 교안을 떼어도 되는지 판정하기 위한 조회 포트(9차 R4).
 *
 * <p>화면도 {@code rules.ts}의 {@code canDelete}·{@code canUnlinkCurriculum}으로 막지만,
 * <b>클라이언트 검증만 있으면 우회된다.</b> 그때 사라지는 것은 학생이 실제로 한 제출·분석·응시다.
 */
public interface ProjectDependencyRepository {

	/**
	 * 이 회차의 팀이 낸 제출이 하나라도 있는지. 제출은 팀 단위 원장이라 team을 거쳐 이어 붙인다.
	 */
	boolean hasSubmissions(UUID projectId);

	/** 이 회차의 응시(measurement_attempt)가 하나라도 있는지. */
	boolean hasAssessmentAttempts(UUID projectId);

	/**
	 * 이 회차의 <b>활성</b> 검증 개념 세트가 그 교안 버전에서 온 매핑을 쓰고 있는지.
	 *
	 * <p>출처가 끊긴 개념은 리포트가 교안 위치를 가리킬 수 없다 — 확정 개념이 쓰는 교안을 떼는 것은
	 * 회차 삭제와 같은 성격이라 함께 막는다.
	 */
	boolean hasConfirmedConceptsFromCurriculum(UUID projectId, UUID curriculumVersionId);
}
