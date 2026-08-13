package com.bigproject.backend.domain.projectexecution.domain;

import java.time.Instant;
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

	/**
	 * 제출 마감 <b>시각</b>을 바꾼다(18차 R5).
	 *
	 * <p>{@code project_assessment_round}는 이 도메인의 JPA 엔티티가 아니라 컬럼 하나만 쓴다 —
	 * {@link ProjectDependencyRepository}의 다른 메서드와 같은 이유로 여기 둔다.
	 *
	 * <p><b>그 프로젝트의 살아 있는 회차 전부</b>를 갱신한다. 정의서가 미니프로젝트에
	 * "활성 회차 정확히 1건"을 요구하므로 지금은 사실상 1행이고, 빅프로젝트가 열려 회차가
	 * 여럿이 되면 회차별 마감을 따로 받는 API가 필요해진다 — 그때 이 메서드는 쓰지 않는다.
	 *
	 * @return 실제로 갱신된 행 수. 0이면 그 프로젝트에 활성 회차가 없다는 뜻이다
	 */
	int updateSubmissionDueAt(UUID projectId, UUID orgId, Instant submissionDueAt);

	/**
	 * 프로젝트를 만들 때 <b>평가 회차 1건을 함께 만든다</b>(22차 R5·R6).
	 *
	 * <p>여태 이 저장소는 {@code project_assessment_round}를 <b>한 번도 만들지 않았다.</b> 회차가 있는
	 * 프로젝트는 전부 시드로 들어간 것이고, 화면에서 만든 프로젝트에는 회차 행이 없었다. 그래서
	 * 두 가지가 동시에 깨져 있었다 — 제출 마감({@code submission_due_at})을 저장할 자리가 없어
	 * 생성에서 마감을 받을 수 없었고, 현황 탭({@code class-progress})은 회차를 못 찾아 답하지 못했다.
	 *
	 * <p>미니프로젝트는 정의서가 "활성 회차 정확히 1건, {@code round_no}=1"을 요구하므로 그대로 만든다.
	 * {@code status}는 {@code PLANNED}로 연다 — {@code ck_project_assessment_round_assessment_window_required}가
	 * {@code PLANNED}가 아닌 회차에 응시 창 두 개를 NOT NULL로 요구하는데, 그 시각은 코드 분석이
	 * 끝나야 정해지므로 생성 시점에는 알 수 없다.
	 *
	 * @param submissionDueAt 제출 마감. DB가 NOT NULL이라 호출부가 반드시 정해서 넘긴다
	 * @return 만들어진 회차 ID
	 */
	UUID createAssessmentRound(UUID projectId, UUID orgId, UUID cohortId, String roundName,
			Instant submissionDueAt, UUID actorUserId);

	/**
	 * 프로젝트별 회차 시각(22차 R5·R9). 목록·상세가 <b>실제 마감</b>을 그리는 근거다.
	 *
	 * <p>여태 화면은 {@code endDate}(날짜)를 마감이라고 그렸는데 그 둘은 서버에서 연결돼 있지 않다 —
	 * 9기 5차가 {@code endDate} 2026-07-31, 실제 마감 8/12로 <b>12일 어긋난</b> 채 표시되고 있었다.
	 *
	 * @return 회차가 없는 프로젝트는 <b>키가 없다</b>. 회차를 아직 만들지 않은 프로젝트가 있을 수 있다
	 */
	Map<UUID, RoundSchedule> findRoundSchedules(Collection<UUID> projectIds);

	/**
	 * 회차 하나의 시각 묶음.
	 *
	 * <p>{@code submissionDueAt}만 NOT NULL이고 나머지 셋은 회차가 열리기 전까지 비어 있다 —
	 * 응시 창은 코드 분석이 끝나야, 리포트 발행 하한은 응시가 닫혀야 정해진다.
	 */
	record RoundSchedule(
			Instant submissionDueAt,
			Instant assessmentOpenAt,
			Instant assessmentDueAt,
			Instant reportPublishNotBeforeAt) {
	}
}
