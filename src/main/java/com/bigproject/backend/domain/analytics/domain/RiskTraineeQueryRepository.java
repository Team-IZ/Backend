package com.bigproject.backend.domain.analytics.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 기수·반 위험 교육생 비율 집계 원천 조회 포트.
 *
 * 테이블 정의서 v07에는 회차 단위 위험 비율을 그대로 주는 View가 없다.
 * operator_cohort_class_risk_view는 grain이 기수 결산 ReportSnapshot이라 회차 축이 없고
 * manager_trainee_risk_view는 cohort_id·class_id가 없어 반·기수로 묶을 수 없다.
 * 따라서 다음 원천 테이블에서 직접 집계한다.
 * - 분모·미집계: measurement_attempt(attempt_type='INITIAL'이 회차·사용자당 유일)
 * - 반 귀속: cohort_member → class_membership → class (회차 시점 기준)
 * - 분자: interview_candidate + interview_candidate_reason
 */
public interface RiskTraineeQueryRepository {
	Optional<CohortScope> findCohortScope(UUID cohortId);

	boolean classroomBelongsToCohort(UUID classroomId, UUID cohortId, UUID organizationId);

	boolean projectBelongsToCohort(UUID projectId, UUID cohortId, UUID organizationId, String projectCategory);

	List<RoundRow> findRounds(RoundCriteria criteria);

	/**
	 * 회차 범위 필터를 적용하기 전의 등록 회차 수.
	 * 화면의 회차 범위 선택지를 그리려면 필터로 잘려 나간 회차까지 세어야 한다.
	 */
	int countRegisteredRounds(RoundCriteria criteria);

	List<RiskCellRow> aggregateRiskCells(RoundCriteria criteria);

	/**
	 * 회차 × 팀 격자. 반 귀속과 달리 project_membership → team_membership → team 경로를 쓴다.
	 * 팀은 프로젝트에 종속이므로 criteria.projectId()와 classroomId가 모두 있어야 한다.
	 */
	List<TeamRiskCellRow> aggregateTeamRiskCells(RoundCriteria criteria, UUID classroomId);

	List<ClassRosterRow> findClassRosters(UUID cohortId, UUID organizationId);

	List<TeamRosterRow> findTeamRosters(UUID projectId, UUID classroomId, UUID organizationId);

	RosterCount findCohortRoster(UUID cohortId, UUID organizationId);

	/**
	 * projectId는 선택이며 null이면 기수의 모든 미니프로젝트를 조회한다.
	 *
	 * fromRoundNo·toRoundNo는 project_assessment_round.round_no가 아니라 project.sequence_no
	 * 범위다. round_no는 (project_id, round_no) UNIQUE라 프로젝트마다 1부터 다시 시작하는데
	 * 미니프로젝트는 프로젝트당 이해도 확인 회차가 1건뿐이라 값이 늘 1이어서 범위 조건이 성립하지
	 * 않는다. 기수의 차수 흐름은 프로젝트 순서에만 남으므로 그쪽을 축으로 삼는다.
	 * 이름은 API 파라미터(fromRoundNo·toRoundNo)와 맞춰 두었다.
	 */
	record RoundCriteria(
			UUID cohortId,
			UUID organizationId,
			String projectCategory,
			UUID projectId,
			int fromRoundNo,
			int toRoundNo
	) {
	}

	/**
	 * @param roundNo       {@code project_assessment_round.round_no} — <b>프로젝트 안에서만</b> 유일하다.
	 *                      미니프로젝트는 회차가 1건뿐이라 늘 1이다
	 * @param cohortRoundNo {@code project.sequence_no} — 기수 안의 회차 순번이며
	 *                      <b>{@link RoundCriteria#fromRoundNo()}·{@link RoundCriteria#toRoundNo()}와 같은 축</b>이다(12차 R1).
	 *                      요청과 응답이 같은 이름의 다른 축을 쓰고 있어 화면이 열 번호를 다시 세고 있었다
	 */
	record RoundRow(
			UUID assessmentRoundId,
			int roundNo,
			int cohortRoundNo,
			String roundName,
			UUID projectId,
			String projectName,
			String roundStatus,
			boolean reportPublished
	) {
	}

	/**
	 * 회차 × 반 격자 한 칸의 원시 집계.
	 * classId가 null인 행은 회차 시점에 반 배정이 없던 교육생이며 기수 전체 집계에는 포함하고 반 행에는 넣지 않는다.
	 *
	 * riskCount는 분자 전체이며 1차 회차에서는 관찰(observedRiskCount)을 포함한다.
	 * observedRiskCount는 그중 관찰로 잡힌 인원이라 riskCount의 부분집합이다.
	 */
	record RiskCellRow(
			UUID assessmentRoundId,
			UUID classId,
			long eligibleCount,
			long riskCount,
			long notAttendedCount,
			long sessionIncompleteCount,
			long invalidAttemptCount,
			long observedRiskCount
	) {
	}

	/**
	 * teamId가 null인 행은 회차 시점에 팀 배정이 없던 교육생이며 팀 행에 넣지 않는다.
	 */
	record TeamRiskCellRow(
			UUID assessmentRoundId,
			UUID teamId,
			long eligibleCount,
			long riskCount,
			long notAttendedCount,
			long sessionIncompleteCount,
			long invalidAttemptCount,
			long observedRiskCount
	) {
	}

	record ClassRosterRow(
			UUID classId,
			String className,
			long traineeCount,
			long withdrawnCount,
			List<String> managerNames
	) {
	}

	record TeamRosterRow(
			UUID teamId,
			String teamNumber,
			String teamName,
			UUID classId,
			String className,
			long memberCount
	) {
	}

	record RosterCount(long traineeCount, long withdrawnCount) {
	}

	record CohortScope(UUID cohortId, UUID organizationId) {
	}
}
