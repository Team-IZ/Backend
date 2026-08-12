package com.bigproject.backend.domain.analytics.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ManagerAnalyticsRepository {
	/** 지금 계층의 비교 단위별 격자다. 행 식별자는 계층에 따라 반·팀·교육생이다. */
	List<HeatmapCell> findHeatmap(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			String level, String attemptView, UUID classroomId, UUID teamId);

	/**
	 * 화면 상단 합계 행이다. 반별 평균을 다시 평균 내면 인원 가중이 깨지므로
	 * <b>개인 단위에서 한 번에</b> 집계한다.
	 */
	List<HeatmapCell> findSummary(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId);

	/** 합계 행의 명부 인원이다. 담당 반 전체 · 그 반 · 그 팀 순으로 좁혀진다. */
	int countMembers(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId);

	List<ConceptAxis> findConcepts(UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId);

	/** 반·문항별 집단 미달 판정이다. 유효 응시자가 없으면 값이 {@code null}이다. */
	List<GroupShortfall> findGroupShortfall(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId);

	List<ClassParticipant> findParticipatingClassrooms(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId);

	List<TeamParticipant> findParticipatingTeams(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId, UUID classroomId);

	List<RiskSignal> findRiskSignals(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID classroomId,
			UUID traineeId, String reasonCode);

	ConceptScope findConceptScope(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID classroomId, UUID teachesId);

	record HeatmapCell(
			UUID rowId, String rowName, int problemNo, BigDecimal value, String status,
			Integer validCount, Integer notAttendedCount, Integer invalidCount, Integer interruptedCount,
			Integer initialLevel, Integer comparisonLevel, Integer delta, OffsetDateTime asOfAt) {
	}

	record ConceptAxis(int problemNo, UUID teachesId, String conceptName) {
	}

	record GroupShortfall(UUID classroomId, int problemNo, Boolean shortfall) {
	}

	record ClassParticipant(UUID classroomId, String className, int memberCount) {
	}

	record TeamParticipant(UUID teamId, String teamName, int memberCount) {
	}

	record RiskSignal(
			UUID signalId, String reasonCode, UUID assessmentRoundId, UUID classroomId,
			UUID teamId, UUID traineeId, String traineeName, String summary,
			String status, int policyVersion, OffsetDateTime detectedAt) {
	}

	record ConceptScope(
			UUID teachesId, String conceptName, long lowLevelCount, long validRespondentCount,
			BigDecimal lowLevelRate, String scope, int policyVersion, OffsetDateTime calculatedAt) {
	}
}
