package com.bigproject.backend.domain.projectexecution.domain;

import java.util.Collection;
import java.util.Map;
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

	/**
	 * 기수 ID → 기수 이름(11차 R5). 회차 라벨에 기수를 붙이는 데 쓴다 —
	 * 교안 하나가 여러 기수에 쓰이면 `미프 1차`만으로는 어느 기수 것인지 알 수 없다.
	 *
	 * <p>cohort는 이 도메인의 엔티티가 아니라 이름 하나만 읽는다.
	 */
	Map<UUID, String> findCohortNames(Collection<UUID> cohortIds);

	/**
	 * 회차별 <b>응시를 시작한</b> 인원(11차 R3). 재분석 경고를 좁히는 기준값이다 —
	 * 0이면 아직 아무도 응시하지 않아 다시 분석해도 발행된 리포트가 어긋나지 않는다.
	 *
	 * <p>"응시 시작"은 {@code primary_attempt_id}가 잡힌 것으로 본다. 완료(COMPLETED)만 세면
	 * 진행 중인 응시가 빠져 <b>경고를 놓친다</b> — 이미 문항을 받은 학생이 있다는 뜻이기 때문이다.
	 *
	 * @return 응시자가 한 명도 없는 회차는 <b>키가 없다</b>
	 */
	Map<UUID, Integer> countAttendedByProject(Collection<UUID> projectIds);
}
