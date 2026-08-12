package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.analytics.domain.ManagerAnalyticsRepository;
import com.bigproject.backend.domain.analytics.presentation.dto.ConceptScopeResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.ManagerHeatmapResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskSignalResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerViewAnalyticsService {
	private final ManagerAnalyticsRepository repository;
	private final ManagerViewScopeGuard scopeGuard;

	public ManagerHeatmapResponse findHeatmap(
			String email, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			ManagerHeatmapResponse.Level level, ManagerHeatmapResponse.AttemptView attemptView,
			UUID classroomId, UUID teamId) {
		validateHeatmap(level, attemptView, classroomId, teamId);
		var actor = scopeGuard.requireCohort(email, cohortId);
		UUID managerId = actor.userId();
		boolean review = attemptView == ManagerHeatmapResponse.AttemptView.REVIEW;

		var shortfall = repository.findGroupShortfall(managerId, cohortId, projectId, assessmentRoundId);
		var classrooms = repository.findParticipatingClassrooms(managerId, cohortId, projectId, assessmentRoundId);
		var summaryCells = repository.findSummary(
				managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
		var cells = repository.findHeatmap(managerId, cohortId, projectId, assessmentRoundId,
				level.name(), attemptView.name(), classroomId, teamId);

		return new ManagerHeatmapResponse(
				cohortId, projectId, assessmentRoundId, level, attemptView,
				latestAsOf(cells, summaryCells),
				buildScope(level, classroomId, teamId, classrooms, managerId, cohortId, projectId, assessmentRoundId),
				buildConcepts(managerId, cohortId, projectId, assessmentRoundId, level, classroomId, shortfall),
				buildSummary(managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId, summaryCells,
						shortfall, level, review),
				buildRows(cells, level, classrooms, managerId, cohortId, projectId, assessmentRoundId,
						classroomId, shortfall, review),
				buildNavigation(level, classrooms, managerId, cohortId, projectId, assessmentRoundId, classroomId));
	}

	/** 고정된 상위 계층만 싣는다. 이름은 참여 반·팀 목록에서 찾아 셀이 반복해 나르지 않게 한다. */
	private ManagerHeatmapResponse.Scope buildScope(
			ManagerHeatmapResponse.Level level, UUID classroomId, UUID teamId,
			List<ManagerAnalyticsRepository.ClassParticipant> classrooms,
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId) {
		if (level == ManagerHeatmapResponse.Level.CLASS) {
			return null;
		}
		String className = classrooms.stream()
				.filter(classroom -> classroom.classroomId().equals(classroomId))
				.map(ManagerAnalyticsRepository.ClassParticipant::className)
				.findFirst().orElse(null);
		if (level == ManagerHeatmapResponse.Level.TEAM) {
			return new ManagerHeatmapResponse.Scope(classroomId, className, null, null);
		}
		String teamName = repository.findParticipatingTeams(
				managerId, cohortId, projectId, assessmentRoundId, classroomId).stream()
				.filter(team -> team.teamId().equals(teamId))
				.map(ManagerAnalyticsRepository.TeamParticipant::teamName)
				.findFirst().orElse(null);
		return new ManagerHeatmapResponse.Scope(classroomId, className, teamId, teamName);
	}

	/**
	 * 열 머리다. {@code groupShortfall}의 기준 집단은 계층마다 다르다 — CLASS는 담당 반 중
	 * 하나라도 미달이면 참이고, TEAM·TRAINEE는 {@code scope}의 그 반이다.
	 */
	private List<ManagerHeatmapResponse.Concept> buildConcepts(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			ManagerHeatmapResponse.Level level, UUID classroomId,
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall) {
		return repository.findConcepts(managerId, cohortId, projectId, assessmentRoundId).stream()
				.map(concept -> new ManagerHeatmapResponse.Concept(
						concept.problemNo(), concept.teachesId(), concept.conceptName(),
						columnShortfall(shortfall, level, classroomId, concept.problemNo())))
				.toList();
	}

	private Boolean columnShortfall(
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall,
			ManagerHeatmapResponse.Level level, UUID classroomId, int problemNo) {
		var matching = shortfall.stream()
				.filter(row -> row.problemNo() == problemNo)
				.filter(row -> level == ManagerHeatmapResponse.Level.CLASS
						|| row.classroomId().equals(classroomId))
				.map(ManagerAnalyticsRepository.GroupShortfall::shortfall)
				.filter(Objects::nonNull)
				.toList();
		return matching.isEmpty() ? null : matching.contains(Boolean.TRUE);
	}

	private ManagerHeatmapResponse.Row buildSummary(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId, List<ManagerAnalyticsRepository.HeatmapCell> summaryCells,
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall,
			ManagerHeatmapResponse.Level level, boolean review) {
		if (summaryCells.isEmpty()) {
			return null;
		}
		int memberCount = repository.countMembers(
				managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
		// 합계 행의 미달 판정은 TEAM·TRAINEE에서만 의미가 있다 — 그때의 합계가 곧 그 반이다.
		UUID shortfallClassroomId = level == ManagerHeatmapResponse.Level.CLASS ? null : classroomId;
		return new ManagerHeatmapResponse.Row(null, null, memberCount,
				summaryCells.stream()
						.map(cell -> toCell(cell, shortfall, shortfallClassroomId, review))
						.toList());
	}

	/** 평면으로 온 셀을 행으로 묶는다. SQL {@code ORDER BY}가 정한 순서를 그대로 보존한다. */
	private List<ManagerHeatmapResponse.Row> buildRows(
			List<ManagerAnalyticsRepository.HeatmapCell> cells, ManagerHeatmapResponse.Level level,
			List<ManagerAnalyticsRepository.ClassParticipant> classrooms,
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, List<ManagerAnalyticsRepository.GroupShortfall> shortfall, boolean review) {
		Map<UUID, Integer> memberCounts = rowMemberCounts(
				level, classrooms, managerId, cohortId, projectId, assessmentRoundId, classroomId);
		Map<UUID, String> names = new LinkedHashMap<>();
		Map<UUID, List<ManagerHeatmapResponse.Cell>> grouped = new LinkedHashMap<>();
		for (var cell : cells) {
			// 반 행일 때만 그 행 자신이 판정 대상이다. 팀은 3~4명이라 한 사람이 판정을 뒤집는다.
			UUID cellClassroomId = level == ManagerHeatmapResponse.Level.CLASS ? cell.rowId() : null;
			names.putIfAbsent(cell.rowId(), cell.rowName());
			grouped.computeIfAbsent(cell.rowId(), rowId -> new ArrayList<>())
					.add(toCell(cell, shortfall, cellClassroomId, review));
		}
		return grouped.entrySet().stream()
				.map(entry -> new ManagerHeatmapResponse.Row(
						entry.getKey(), names.get(entry.getKey()),
						memberCounts.get(entry.getKey()), List.copyOf(entry.getValue())))
				.toList();
	}

	private Map<UUID, Integer> rowMemberCounts(
			ManagerHeatmapResponse.Level level,
			List<ManagerAnalyticsRepository.ClassParticipant> classrooms,
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId, UUID classroomId) {
		return switch (level) {
			case CLASS -> classrooms.stream().collect(LinkedHashMap::new,
					(map, classroom) -> map.put(classroom.classroomId(), classroom.memberCount()),
					LinkedHashMap::putAll);
			case TEAM -> repository.findParticipatingTeams(
					managerId, cohortId, projectId, assessmentRoundId, classroomId).stream()
					.collect(LinkedHashMap::new,
							(map, team) -> map.put(team.teamId(), team.memberCount()),
							LinkedHashMap::putAll);
			// 개인 행은 인원 개념이 없다.
			case TRAINEE -> Map.of();
		};
	}

	private ManagerHeatmapResponse.Cell toCell(
			ManagerAnalyticsRepository.HeatmapCell cell,
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall,
			UUID shortfallClassroomId, boolean review) {
		return new ManagerHeatmapResponse.Cell(
				cell.problemNo(), cell.value(), cell.status(),
				cell.validCount(), cell.notAttendedCount(), cell.invalidCount(), cell.interruptedCount(),
				shortfallClassroomId == null ? null : lookupShortfall(shortfall, shortfallClassroomId, cell.problemNo()),
				review ? cell.initialLevel() : null,
				review ? cell.comparisonLevel() : null,
				review ? cell.delta() : null);
	}

	private Boolean lookupShortfall(
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall, UUID classroomId, int problemNo) {
		return shortfall.stream()
				.filter(row -> row.problemNo() == problemNo && row.classroomId().equals(classroomId))
				.map(ManagerAnalyticsRepository.GroupShortfall::shortfall)
				.findFirst().orElse(null);
	}

	/** 집계 시각은 응답 전체의 성질이라 셀마다 나르지 않는다. */
	private OffsetDateTime latestAsOf(
			List<ManagerAnalyticsRepository.HeatmapCell> cells,
			List<ManagerAnalyticsRepository.HeatmapCell> summaryCells) {
		return Stream.concat(cells.stream(), summaryCells.stream())
				.map(ManagerAnalyticsRepository.HeatmapCell::asOfAt)
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder())
				.orElse(null);
	}

	/** CLASS 계층은 {@code rows}가 곧 반 목록이라 셀렉터 데이터를 싣지 않는다. */
	private ManagerHeatmapResponse.Navigation buildNavigation(
			ManagerHeatmapResponse.Level level,
			List<ManagerAnalyticsRepository.ClassParticipant> classrooms,
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId, UUID classroomId) {
		if (level == ManagerHeatmapResponse.Level.CLASS) {
			return new ManagerHeatmapResponse.Navigation(List.of(), List.of());
		}
		var classroomOptions = classrooms.stream()
				.map(classroom -> new ManagerHeatmapResponse.Classroom(
						classroom.classroomId(), classroom.className(), classroom.memberCount()))
				.toList();
		if (level == ManagerHeatmapResponse.Level.TEAM) {
			return new ManagerHeatmapResponse.Navigation(classroomOptions, List.of());
		}
		var teamOptions = repository.findParticipatingTeams(
				managerId, cohortId, projectId, assessmentRoundId, classroomId).stream()
				.map(team -> new ManagerHeatmapResponse.Team(
						team.teamId(), team.teamName(), team.memberCount()))
				.toList();
		return new ManagerHeatmapResponse.Navigation(classroomOptions, teamOptions);
	}

	public RiskSignalResponse findRiskSignals(
			String email, UUID cohortId, UUID assessmentRoundId, UUID classroomId,
			UUID traineeId, String reasonCode) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		var signals = repository.findRiskSignals(actor.userId(), cohortId, assessmentRoundId,
				classroomId, traineeId, reasonCode).stream()
				.map(signal -> new RiskSignalResponse.Signal(
						signal.signalId(), signal.reasonCode(), signal.assessmentRoundId(),
						signal.classroomId(), signal.teamId(), signal.traineeId(), signal.traineeName(),
						signal.summary(), signal.status(), signal.policyVersion(), signal.detectedAt()))
				.toList();
		return new RiskSignalResponse(cohortId, signals);
	}

	public ConceptScopeResponse findConceptScope(
			String email, UUID cohortId, UUID assessmentRoundId, UUID classroomId, UUID teachesId) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		var result = repository.findConceptScope(actor.userId(), cohortId, assessmentRoundId, classroomId, teachesId);
		if (result == null) {
			throw new ApiException(AnalyticsErrorCode.CONCEPT_SCOPE_NOT_FOUND);
		}
		return new ConceptScopeResponse(cohortId, assessmentRoundId, classroomId, result.teachesId(),
				result.conceptName(), result.lowLevelCount(), result.validRespondentCount(), result.lowLevelRate(),
				ConceptScopeResponse.Scope.valueOf(result.scope()), result.policyVersion(), result.calculatedAt());
	}

	private void validateHeatmap(
			ManagerHeatmapResponse.Level level, ManagerHeatmapResponse.AttemptView attemptView,
			UUID classroomId, UUID teamId) {
		if (attemptView == ManagerHeatmapResponse.AttemptView.REVIEW
				&& level != ManagerHeatmapResponse.Level.TRAINEE) {
			throw new ApiException(AnalyticsErrorCode.HEATMAP_REVIEW_TRAINEE_REQUIRED);
		}
		boolean invalid = switch (level) {
			case CLASS -> classroomId != null || teamId != null;
			case TEAM -> classroomId == null || teamId != null;
			case TRAINEE -> classroomId == null || teamId == null;
		};
		if (invalid) {
			throw new ApiException(AnalyticsErrorCode.HEATMAP_SCOPE_INVALID);
		}
	}
}
