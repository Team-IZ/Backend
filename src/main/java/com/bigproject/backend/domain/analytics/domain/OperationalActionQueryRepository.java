package com.bigproject.backend.domain.analytics.domain;

import java.util.List;
import java.util.UUID;

/**
 * 오퍼레이터 대시보드 '조치 필요' 경보와 집단 미달 목록의 원천 조회 포트.
 *
 * operational_alert View는 이 화면과 대응하지 않는다. View가 담은 네 경보 중 화면과 겹치는 것은
 * MANAGER_UNASSIGNED 하나뿐이고, 나머지 셋(PROJECT_NOT_READY·ANALYSIS_FAILED·SUBMISSION_OVERDUE)은
 * 대시보드 상단 회차 요약으로 흡수됐다. 화면이 요구하는 개념 공백·집단 미달·면담 적체는
 * View에 없으므로 원천 테이블에서 직접 집계한다.
 *
 * 경보는 파생 조회다. 정의서가 "독립 수동 상태 머신과 별도 물리 경보 원장은 두지 않습니다"라고
 * 못 박으므로 해소·무시 상태를 저장하지 않는다. 원인이 사라지면 행이 사라진다.
 */
public interface OperationalActionQueryRepository {

	/**
	 * 활성 담당 매니저가 없는 반. 종료된 반은 제외한다.
	 */
	List<UnassignedClassRow> findUnassignedClasses(UUID cohortId, UUID organizationId);

	/**
	 * 검증 개념별로 코드 근거를 찾지 못한 팀 수. 코드 매칭 0 판정은 계산이 아니라 읽기다.
	 * assessment_problem의 CHECK가 NOT_GENERATED의 사유를 NO_MATCHING_CODE_EVIDENCE 하나로 고정한다.
	 */
	List<ConceptGapRow> findConceptGaps(UUID cohortId, UUID organizationId);

	/**
	 * 회차 × 반 × 검증 개념별 2단 이하 인원. 미달 판정은 GroupGapPolicy가 맡는다.
	 * 평가가 이뤄진 조합 수를 함께 돌려주어 '평가 없음'과 '미달 0건'을 구분한다.
	 */
	List<GroupGapRow> findGroupGaps(UUID cohortId, UUID organizationId);

	/**
	 * 반별로 면담 예정일이 지났는데 아직 시작되지 않은 면담의 최대 지연일.
	 */
	List<InterviewBacklogRow> findInterviewBacklogs(UUID cohortId, UUID organizationId);

	record RoundRef(
			UUID assessmentRoundId,
			int roundNo,
			String roundName,
			UUID projectId,
			String projectName
	) {
	}

	record UnassignedClassRow(UUID classId, String className, long traineeCount) {
	}

	record ConceptGapRow(
			RoundRef round,
			UUID teachesId,
			String conceptName,
			long gapTeamCount,
			long participatingTeamCount
	) {
	}

	record GroupGapRow(
			RoundRef round,
			UUID classId,
			String className,
			UUID teachesId,
			String conceptName,
			long lowLevelCount,
			long classMemberCount
	) {
	}

	/**
	 * notCreatedCount와 unplannedCount는 지연일을 계산할 수 없어 경보 판정에서 빠진 인원이다.
	 * 면담이 아직 생성되지 않았거나 예정일이 잡히지 않아 기산점이 없다.
	 */
	record InterviewBacklogRow(
			RoundRef round,
			UUID classId,
			String className,
			int maxDelayDays,
			long pendingInterviewCount,
			long notCreatedCount,
			long unplannedCount
	) {
	}
}
