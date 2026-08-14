package com.bigproject.backend.domain.member.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MG-06 교육생 상세의 헤더·회차 격자를 읽는다. 원천은 명단과 같은
 * {@code manager_trainee_roster_view}이며 <b>회차 축으로 이미 펼쳐져 있어</b>
 * 교육생 하나를 고르면 그 기수의 회차가 행으로 그대로 나온다 —
 * 상세 전용 뷰를 따로 만들지 않는다(같은 값을 두 곳에서 계산하지 않는다).
 */
public interface TraineeDetailRepository {

	/**
	 * 매니저 담당 범위 안에서 교육생 한 명의 회차 행 전부. 차수 오름차순이며
	 * 기수에 회차가 하나도 없으면 회차 필드가 전부 비어 있는 행 하나가 온다.
	 * 담당 범위 밖이거나 없는 교육생이면 빈 목록이다.
	 */
	List<DetailRow> findRounds(UUID managerId, UUID cohortId, UUID traineeId);

	record DetailRow(
			UUID traineeId, String name, String email,
			UUID cohortId, String cohortName, String rawAccountStatus,
			UUID classroomId, String className,
			String inactivatedReasonCode, String inactivatedReason, OffsetDateTime inactivatedAt,
			UUID assessmentRoundId, Integer cohortRoundNo, Integer roundNo,
			String roundName, UUID projectId, String projectName,
			UUID attemptId, String roundResultStatus, String primaryStatusCode,
			List<String> matchedRiskTypeCodes, String riskReasonSummary,
			OffsetDateTime roundTerminalAt, String conceptResultItems,
			Integer expectedConceptCount, Integer lowStageConceptCount,
			Integer excellentOccurrenceCount, int[] excellentAssessmentSequenceNos,
			UUID teamIdAtRound, String rowAggregationStatus) {
	}
}
