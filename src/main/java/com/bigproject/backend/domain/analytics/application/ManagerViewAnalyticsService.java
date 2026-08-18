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
	/** 더할 것이 없는 자리에 쓰는 빈 묶음. {@code null} 검사를 셀마다 반복하지 않기 위해 둔다. */
	private static final ManagerAnalyticsRepository.UnresolvedGroup NO_EXTRA =
			new ManagerAnalyticsRepository.UnresolvedGroup(null, null, 0, 0, 0, 0);

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
		var concepts = repository.findConcepts(managerId, cohortId, projectId, assessmentRoundId);
		var summaryCells = repository.findSummary(
				managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
		var cells = repository.findHeatmap(managerId, cohortId, projectId, assessmentRoundId,
				level.name(), attemptView.name(), classroomId, teamId);
		/*
		 * 34차 R2·R3 — 결과가 하나도 없는 인원.
		 *
		 * REVIEW는 재시험을 친 사람만 보는 화면이라 「결과가 없는 사람」이라는 개념이 성립하지
		 * 않는다(안 친 사람이 대부분이고, 그것이 정상이다). INITIAL에서만 센다.
		 */
		var unresolved = review ? List.<ManagerAnalyticsRepository.UnresolvedGroup>of()
				: repository.findUnresolved(managerId, cohortId, projectId, assessmentRoundId,
						level.name(), classroomId, teamId);

		return new ManagerHeatmapResponse(
				cohortId, projectId, assessmentRoundId, level, attemptView,
				latestAsOf(cells, summaryCells),
				buildScope(level, classroomId, teamId, classrooms, managerId, cohortId, projectId, assessmentRoundId),
				buildConcepts(concepts, level, classroomId, shortfall),
				buildSummary(managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId, summaryCells,
						shortfall, level, review, concepts, unresolved),
				buildRows(cells, level, classrooms, managerId, cohortId, projectId, assessmentRoundId,
						classroomId, shortfall, review, concepts, unresolved),
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
		/*
		 * 32차 R13 — 이 회차에 없는 팀이면 400이다.
		 *
		 * 팀은 프로젝트에 매인 값이라 회차가 바뀌면 뜻을 잃는다. 종전에는 다른 회차의 teamId를
		 * 줘도 200이 나갔고, rows가 비고 scope.teamName만 null이었다. 그러면 응답만 보고는
		 * 「그 팀에 결과가 없다」와 「그 팀이 이 회차에 없다」가 구분되지 않아, 화면이 앞의 뜻으로
		 * 읽고 "이 범위에는 아직 결과가 없습니다"라는 사실 아닌 안내를 그린다.
		 *
		 * validateHeatmap은 null 조합만 보므로 여기서 막는다 — 이 조회가 그 회차의 참여 팀
		 * 목록이라 존재 여부를 아는 유일한 자리다. 팀 없이 보낼 때와 같은 코드를 쓴다.
		 */
		String teamName = repository.findParticipatingTeams(
						managerId, cohortId, projectId, assessmentRoundId, classroomId).stream()
				.filter(team -> team.teamId().equals(teamId))
				.map(ManagerAnalyticsRepository.TeamParticipant::teamName)
				.findFirst()
				.orElseThrow(() -> new ApiException(AnalyticsErrorCode.HEATMAP_SCOPE_INVALID));
		return new ManagerHeatmapResponse.Scope(classroomId, className, teamId, teamName);
	}

	/**
	 * 열 머리다. {@code groupShortfall}의 기준 집단은 계층마다 다르다 — CLASS는 담당 반 중
	 * 하나라도 미달이면 참이고, TEAM·TRAINEE는 {@code scope}의 그 반이다.
	 */
	private List<ManagerHeatmapResponse.Concept> buildConcepts(
			List<ManagerAnalyticsRepository.ConceptAxis> concepts,
			ManagerHeatmapResponse.Level level, UUID classroomId,
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall) {
		return concepts.stream()
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

	/**
	 * 합계 행이다.
	 *
	 * <h2>34차 R2 — 세 카운터로 설명되지 않던 차이를 없앤다</h2>
	 *
	 * <p>종전에는 {@code memberCount 5}인데 {@code rows}가 4행이고 미응시·무효·중단이 전부 0인
	 * 상태가 나왔다. 결과가 하나도 없는 사람이 <b>집계 질의에서도 빠져</b> 있었기 때문이다.
	 * 이제 그 인원을 세 카운터에 더하고, 그러고도 남는 차이를 {@code notInRoundCount}로 낸다.
	 *
	 * <p>결과가 <b>하나도 없는 범위</b>에서도 합계 행을 만든다(34차 R3). 팀 전원이 분석 실패면
	 * 종전에는 {@code summary}가 통째로 {@code null}이라 화면이 그 팀을 그릴 수 없었다.
	 */
	private ManagerHeatmapResponse.Row buildSummary(
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, UUID teamId, List<ManagerAnalyticsRepository.HeatmapCell> summaryCells,
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall,
			ManagerHeatmapResponse.Level level, boolean review,
			List<ManagerAnalyticsRepository.ConceptAxis> concepts,
			List<ManagerAnalyticsRepository.UnresolvedGroup> unresolved) {
		var extra = fold(unresolved);
		if (summaryCells.isEmpty() && extra.total() == 0) {
			return null;
		}
		int memberCount = repository.countMembers(
				managerId, cohortId, projectId, assessmentRoundId, classroomId, teamId);
		// 합계 행의 미달 판정은 TEAM·TRAINEE에서만 의미가 있다 — 그때의 합계가 곧 그 반이다.
		UUID shortfallClassroomId = level == ManagerHeatmapResponse.Level.CLASS ? null : classroomId;

		List<ManagerHeatmapResponse.Cell> cells = summaryCells.isEmpty()
				// 유효 결과가 한 건도 없다 — 개념 축만으로 열을 세운다.
				? concepts.stream()
						.map(concept -> emptyCell(concept.problemNo(), extra, memberCount))
						.toList()
				: summaryCells.stream()
						.map(cell -> toCell(cell, shortfall, shortfallClassroomId, review, extra, memberCount))
						.toList();
		return new ManagerHeatmapResponse.Row(null, null, memberCount, cells);
	}

	/** 계층별로 흩어진 「결과 없는 인원」을 하나로 접는다. 합계 행이 쓰는 값이다. */
	private ManagerAnalyticsRepository.UnresolvedGroup fold(
			List<ManagerAnalyticsRepository.UnresolvedGroup> unresolved) {
		int notAttended = 0;
		int invalid = 0;
		int interrupted = 0;
		int pending = 0;
		for (var group : unresolved) {
			notAttended += group.notAttendedCount();
			invalid += group.invalidCount();
			interrupted += group.interruptedCount();
			pending += group.pendingCount();
		}
		return new ManagerAnalyticsRepository.UnresolvedGroup(
				null, null, notAttended, invalid, interrupted, pending);
	}

	/**
	 * 유효 결과가 한 건도 없는 자리의 셀이다.
	 *
	 * <p>{@code value}는 {@code null}이다 — 평균을 낼 표본이 없다. 상태는 뷰의 집계 상태와 같은
	 * 말({@code NO_VALID_RESULT})을 써서, 화면이 이 셀만 다르게 읽지 않게 한다.
	 */
	private ManagerHeatmapResponse.Cell emptyCell(
			int problemNo, ManagerAnalyticsRepository.UnresolvedGroup extra, Integer memberCount) {
		return new ManagerHeatmapResponse.Cell(
				problemNo, null, "NO_VALID_RESULT",
				0, extra.notAttendedCount(), extra.invalidCount(), extra.interruptedCount(),
				residual(memberCount, 0, extra), null, null, null, null);
	}

	/** 명부에는 있는데 격자에 자리가 없는 인원. 합계 행에만 채운다. */
	private Integer residual(
			Integer memberCount, int gridCount, ManagerAnalyticsRepository.UnresolvedGroup extra) {
		return memberCount == null ? null : Math.max(0, memberCount - gridCount - extra.total());
	}

	/** 평면으로 온 셀을 행으로 묶는다. SQL {@code ORDER BY}가 정한 순서를 그대로 보존한다. */
	private List<ManagerHeatmapResponse.Row> buildRows(
			List<ManagerAnalyticsRepository.HeatmapCell> cells, ManagerHeatmapResponse.Level level,
			List<ManagerAnalyticsRepository.ClassParticipant> classrooms,
			UUID managerId, UUID cohortId, UUID projectId, UUID assessmentRoundId,
			UUID classroomId, List<ManagerAnalyticsRepository.GroupShortfall> shortfall, boolean review,
			List<ManagerAnalyticsRepository.ConceptAxis> concepts,
			List<ManagerAnalyticsRepository.UnresolvedGroup> unresolved) {
		Map<UUID, Integer> memberCounts = rowMemberCounts(
				level, classrooms, managerId, cohortId, projectId, assessmentRoundId, classroomId);
		Map<UUID, ManagerAnalyticsRepository.UnresolvedGroup> extraByRow = new LinkedHashMap<>();
		for (var group : unresolved) {
			// rowId가 없는 묶음은 그릴 자리가 없다. 합계에서만 센다(findUnresolved 설명 참고).
			if (group.rowId() != null) {
				extraByRow.put(group.rowId(), group);
			}
		}
		Map<UUID, String> names = new LinkedHashMap<>();
		Map<UUID, List<ManagerHeatmapResponse.Cell>> grouped = new LinkedHashMap<>();
		for (var cell : cells) {
			// 반 행일 때만 그 행 자신이 판정 대상이다. 팀은 3~4명이라 한 사람이 판정을 뒤집는다.
			UUID cellClassroomId = level == ManagerHeatmapResponse.Level.CLASS ? cell.rowId() : null;
			names.putIfAbsent(cell.rowId(), cell.rowName());
			grouped.computeIfAbsent(cell.rowId(), rowId -> new ArrayList<>())
					.add(toCell(cell, shortfall, cellClassroomId, review,
							extraByRow.getOrDefault(cell.rowId(), NO_EXTRA), null));
		}

		List<ManagerHeatmapResponse.Row> rows = new ArrayList<>(grouped.size());
		for (var entry : grouped.entrySet()) {
			rows.add(new ManagerHeatmapResponse.Row(entry.getKey(), names.get(entry.getKey()),
					memberCounts.get(entry.getKey()), List.copyOf(entry.getValue())));
		}

		/*
		 * 34차 R3 — 결과가 한 건도 없어 통째로 빠져 있던 행을 되살린다.
		 *
		 * 2차 C반 4팀이 그랬다. 팀원 넷 모두 분석이 실패해(FAILED · ANALYSIS_FAILED) 문제 결과가
		 * 없었고, 그러면 격자 질의가 그 팀을 한 줄도 만들지 못한다. 머리글은 「전체 26명」인데 팀을
		 * 다 더하면 22명이고, 팀 드롭다운에도 없어 그 팀으로 들어갈 방법이 사라진다.
		 */
		List<ManagerHeatmapResponse.Row> missing = new ArrayList<>();
		for (var group : unresolved) {
			if (group.rowId() == null || group.rowName() == null || grouped.containsKey(group.rowId())) {
				continue;
			}
			missing.add(new ManagerHeatmapResponse.Row(
					group.rowId(), group.rowName(), memberCounts.get(group.rowId()),
					emptyRowCells(level, concepts, group)));
		}
		if (missing.isEmpty()) {
			return List.copyOf(rows);
		}
		/*
		 * 되살린 행이 있을 때만 다시 정렬한다. 없으면 SQL의 ORDER BY를 그대로 두는 편이 안전하다 —
		 * DB 콜레이션과 자바 문자열 비교가 반드시 같지는 않아, 평소 응답의 행 순서를 이 코드가
		 * 흔들지 않게 한다.
		 */
		rows.addAll(missing);
		rows.sort(Comparator.comparing(ManagerHeatmapResponse.Row::rowName,
				Comparator.nullsLast(Comparator.naturalOrder())));
		return List.copyOf(rows);
	}

	/**
	 * 결과가 한 건도 없는 행의 셀이다.
	 *
	 * <p>개인 행은 카운터 없이 <b>그 사람의 상태</b>를 싣는다 — 화면이 「미응시」·「응시 중단」을
	 * 그대로 그릴 수 있어야 한다. 반·팀 행은 집계 행이라 카운터를 채운다.
	 */
	private List<ManagerHeatmapResponse.Cell> emptyRowCells(
			ManagerHeatmapResponse.Level level,
			List<ManagerAnalyticsRepository.ConceptAxis> concepts,
			ManagerAnalyticsRepository.UnresolvedGroup group) {
		if (level == ManagerHeatmapResponse.Level.TRAINEE) {
			String status = group.dominantStatus();
			return concepts.stream()
					.map(concept -> new ManagerHeatmapResponse.Cell(
							concept.problemNo(), null, status,
							null, null, null, null, null, null, null, null, null))
					.toList();
		}
		return concepts.stream()
				.map(concept -> emptyCell(concept.problemNo(), group, null))
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

	/**
	 * 격자 셀 하나.
	 *
	 * <p>{@code extra}는 이 셀이 대표하는 범위에서 <b>결과가 하나도 없던 인원</b>이다(34차 R2).
	 * 세 카운터에 더한다 — 그 사람들도 미응시·무효·중단 중 하나이지 「없는 사람」이 아니다.
	 * 개인 셀은 애초에 카운터가 {@code null}이라 더할 자리가 없고, 그대로 {@code null}로 둔다.
	 */
	private ManagerHeatmapResponse.Cell toCell(
			ManagerAnalyticsRepository.HeatmapCell cell,
			List<ManagerAnalyticsRepository.GroupShortfall> shortfall,
			UUID shortfallClassroomId, boolean review,
			ManagerAnalyticsRepository.UnresolvedGroup extra, Integer memberCount) {
		// 잔여 인원은 「더하기 전」의 격자 인원으로 센다 — extra는 residual이 다시 뺀다.
		int gridCount = zero(cell.validCount()) + zero(cell.notAttendedCount())
				+ zero(cell.invalidCount()) + zero(cell.interruptedCount());
		return new ManagerHeatmapResponse.Cell(
				cell.problemNo(), cell.value(), cell.status(),
				cell.validCount(),
				plus(cell.notAttendedCount(), extra.notAttendedCount()),
				plus(cell.invalidCount(), extra.invalidCount()),
				plus(cell.interruptedCount(), extra.interruptedCount()),
				residual(memberCount, gridCount, extra),
				shortfallClassroomId == null ? null : lookupShortfall(shortfall, shortfallClassroomId, cell.problemNo()),
				review ? cell.initialLevel() : null,
				review ? cell.comparisonLevel() : null,
				review ? cell.delta() : null);
	}

	private Integer plus(Integer base, int add) {
		return base == null ? null : base + add;
	}

	private int zero(Integer value) {
		return value == null ? 0 : value;
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

	// =========================================================
	// MG-01 인박스 행 근거 위험 신호 조회 (구 findRiskSignals와 통합, 5번 정리)
	// assessmentRoundId·reasonCode는 구 /risk-signals가 갖고 있던 필터를 그대로 흡수한 것 —
	// 지금 화면들은 안 쓰지만 나중에 회차별 필터가 필요해질 때 대비해 남겨둔다.
	// =========================================================
	public RiskSignalResponse getRiskSignalsForInbox(
			String email, UUID cohortId, UUID classId, UUID traineeId,
			UUID assessmentRoundId, String reasonCode) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		var signals = repository.findRiskSignals(actor.userId(), cohortId, assessmentRoundId,
						classId, traineeId, reasonCode).stream()
				.map(signal -> new RiskSignalResponse.Signal(
						signal.signalId(), signal.reasonCode(), signal.assessmentRoundId(),
						signal.classroomId(), signal.teamId(), signal.traineeId(), signal.traineeName(),
						signal.summary(), signal.status(), signal.policyVersion(), signal.detectedAt()))
				.toList();
		return new RiskSignalResponse(cohortId, signals);
	}
}