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

	List<RoundRow> findRounds(RoundCriteria criteria);

	List<RiskCellRow> aggregateRiskCells(RoundCriteria criteria);

	List<ClassRosterRow> findClassRosters(UUID cohortId, UUID organizationId);

	RosterCount findCohortRoster(UUID cohortId, UUID organizationId);

	record RoundCriteria(
			UUID cohortId,
			UUID organizationId,
			String projectCategory,
			int fromRoundNo,
			int toRoundNo
	) {
	}

	record RoundRow(
			UUID assessmentRoundId,
			int roundNo,
			String roundName,
			UUID projectId,
			String projectName,
			String roundStatus
	) {
	}

	/**
	 * 회차 × 반 격자 한 칸의 원시 집계.
	 * classId가 null인 행은 회차 시점에 반 배정이 없던 교육생이며 기수 전체 집계에는 포함하고 반 행에는 넣지 않는다.
	 */
	record RiskCellRow(
			UUID assessmentRoundId,
			UUID classId,
			long eligibleCount,
			long riskCount,
			long notAttendedCount,
			long sessionIncompleteCount,
			long invalidAttemptCount
	) {
	}

	record ClassRosterRow(
			UUID classId,
			String className,
			long traineeCount,
			long withdrawnCount
	) {
	}

	record RosterCount(long traineeCount, long withdrawnCount) {
	}

	record CohortScope(UUID cohortId, UUID organizationId) {
	}
}
