package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.analytics.domain.ManagerAnalyticsRepository;
import com.bigproject.backend.domain.analytics.presentation.dto.ManagerHeatmapResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagerViewAnalyticsServiceTest {
	private final ManagerAnalyticsRepository repository = mock(ManagerAnalyticsRepository.class);
	private final ManagerViewScopeGuard scopeGuard = mock(ManagerViewScopeGuard.class);
	private final ManagerViewAnalyticsService service = new ManagerViewAnalyticsService(repository, scopeGuard);
	private final UUID cohortId = UUID.randomUUID();
	private final UUID managerId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID classroomId = UUID.randomUUID();
	private final UUID teamId = UUID.randomUUID();
	// 가로축은 개념이다. 문제 순번은 팀 분석마다 다시 매겨져 축이 될 수 없다.
	private final UUID jwtId = UUID.randomUUID();
	private final UUID jpaId = UUID.randomUUID();
	private final OffsetDateTime asOf = OffsetDateTime.parse("2026-08-11T23:18:17Z");

	@BeforeEach
	void grantScope() {
		lenient().when(scopeGuard.requireCohort("manager@example.com", cohortId))
				.thenReturn(new ManagerViewScopeGuard.ManagerActor(managerId, UUID.randomUUID()));
	}

	@Test
	void reviewHeatmapRequiresTraineeLevel() {
		assertThatThrownBy(() -> service.findHeatmap("manager@example.com", cohortId,
				projectId, roundId, ManagerHeatmapResponse.Level.CLASS,
				ManagerHeatmapResponse.AttemptView.REVIEW, null, null))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.HEATMAP_REVIEW_TRAINEE_REQUIRED));
		verifyNoInteractions(scopeGuard, repository);
	}

	@Test
	void traineeHeatmapRequiresClassroomAndTeam() {
		assertThatThrownBy(() -> service.findHeatmap("manager@example.com", cohortId,
				projectId, roundId, ManagerHeatmapResponse.Level.TRAINEE,
				ManagerHeatmapResponse.AttemptView.INITIAL, classroomId, null))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.HEATMAP_SCOPE_INVALID));
	}

	@Test
	void classHeatmapGroupsCellsIntoRowsAndDropsRepeatedIdentity() {
		stubConceptsAndClassrooms();
		when(repository.findGroupShortfall(managerId, cohortId, projectId, roundId)).thenReturn(List.of(
				new ManagerAnalyticsRepository.GroupShortfall(classroomId, jwtId, false),
				new ManagerAnalyticsRepository.GroupShortfall(classroomId, jpaId, true)));
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"CLASS", "INITIAL", null, null)).thenReturn(List.of(
				cell(classroomId, "C반", jwtId, "2.625"), cell(classroomId, "C반", jpaId, "2.25")));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.CLASS, ManagerHeatmapResponse.AttemptView.INITIAL, null, null);

		// 셀 2건이 반 1행으로 접힌다 — 식별자를 셀마다 반복하지 않는다.
		assertThat(response.rows()).singleElement().satisfies(row -> {
			assertThat(row.rowId()).isEqualTo(classroomId);
			assertThat(row.rowName()).isEqualTo("C반");
			assertThat(row.memberCount()).isEqualTo(26);
			assertThat(row.cells()).extracting(ManagerHeatmapResponse.Cell::problemNo).containsExactly(1, 2);
			assertThat(row.cells()).extracting(ManagerHeatmapResponse.Cell::groupShortfall)
					.containsExactly(false, true);
		});
		assertThat(response.scope()).isNull();
		assertThat(response.asOfAt()).isEqualTo(asOf);
		// CLASS에서는 rows가 곧 반 목록이라 셀렉터를 싣지 않는다.
		assertThat(response.navigation().classrooms()).isEmpty();
	}

	/**
	 * 히트맵 머리글 5칸 · 셀 3칸으로 표가 끊겨 보이던 것.
	 *
	 * <p>문제 순번을 가로축으로 쓴 것이 원인이었다. 순번은 팀 분석마다 다시 매겨져 한 회차 안에서도
	 * 팀에 따라 1번이 가리키는 개념이 다르고, 열 머리는 {@code (순번, 개념)} 조합이라 개념 수보다
	 * 많아지는데 셀은 순번으로만 묶여 그대로였다.
	 *
	 * <p>이제 두 배열이 <b>같은 개념 축</b>에서 나온다. 결과가 없는 개념은 빈 셀로 자리를 채워
	 * 어떤 행이든 열 머리와 길이가 같다.
	 */
	@Test
	void everyRowHasOneCellPerConceptInTheSameOrder() {
		UUID classroomId2 = UUID.randomUUID();
		stubConceptsAndClassrooms();
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"CLASS", "INITIAL", null, null)).thenReturn(List.of(
				// C반은 두 개념이 다 있고, D반은 JPA 하나뿐이다.
				cell(classroomId, "C반", jpaId, "2.0"), cell(classroomId, "C반", jwtId, "2.4"),
				cell(classroomId2, "D반", jpaId, "1.5")));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.CLASS, ManagerHeatmapResponse.AttemptView.INITIAL, null, null);

		var axis = response.concepts().stream().map(ManagerHeatmapResponse.Concept::teachesId).toList();
		assertThat(axis).containsExactly(jwtId, jpaId);
		assertThat(response.rows()).allSatisfy(row -> {
			// 셀이 도착한 순서가 아니라 열 머리 순서로 선다.
			assertThat(row.cells()).extracting(ManagerHeatmapResponse.Cell::teachesId)
					.containsExactlyElementsOf(axis);
			assertThat(row.cells()).extracting(ManagerHeatmapResponse.Cell::problemNo)
					.containsExactly(1, 2);
		});
		// 값이 없는 개념도 자리는 있다 — 표가 끊기지 않는다.
		assertThat(response.rows().get(1).cells().get(0).value()).isNull();
		assertThat(response.rows().get(1).cells().get(0).status()).isEqualTo("NO_VALID_RESULT");
		assertThat(response.rows().get(1).cells().get(1).value()).isEqualByComparingTo("1.5");
	}

	@Test
	void classColumnHeaderFlagsShortfallWhenAnyClassroomFails() {
		UUID otherClassroomId = UUID.randomUUID();
		stubConceptsAndClassrooms();
		when(repository.findGroupShortfall(managerId, cohortId, projectId, roundId)).thenReturn(List.of(
				new ManagerAnalyticsRepository.GroupShortfall(classroomId, jwtId, false),
				new ManagerAnalyticsRepository.GroupShortfall(otherClassroomId, jwtId, true),
				new ManagerAnalyticsRepository.GroupShortfall(classroomId, jpaId, false)));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.CLASS, ManagerHeatmapResponse.AttemptView.INITIAL, null, null);

		assertThat(response.concepts()).extracting(ManagerHeatmapResponse.Concept::groupShortfall)
				.containsExactly(true, false);
	}

	@Test
	void teamHeatmapHoistsClassroomIntoScopeAndLeavesTeamRowsUnjudged() {
		stubConceptsAndClassrooms();
		when(repository.findGroupShortfall(managerId, cohortId, projectId, roundId)).thenReturn(List.of(
				new ManagerAnalyticsRepository.GroupShortfall(classroomId, jwtId, true)));
		when(repository.findParticipatingTeams(managerId, cohortId, projectId, roundId, classroomId))
				.thenReturn(List.of(new ManagerAnalyticsRepository.TeamParticipant(teamId, "2팀", 3)));
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"TEAM", "INITIAL", classroomId, null)).thenReturn(List.of(cell(teamId, "2팀", jwtId, "1.7")));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.TEAM, ManagerHeatmapResponse.AttemptView.INITIAL, classroomId, null);

		assertThat(response.scope()).isEqualTo(
				new ManagerHeatmapResponse.Scope(classroomId, "C반", null, null));
		assertThat(response.rows()).singleElement().satisfies(row -> {
			assertThat(row.rowId()).isEqualTo(teamId);
			assertThat(row.memberCount()).isEqualTo(3);
			// 팀은 3~4명이라 한 사람이 판정을 뒤집는다 — 팀 행에는 미달을 달지 않는다.
			assertThat(row.cells()).extracting(ManagerHeatmapResponse.Cell::groupShortfall)
					.containsOnlyNulls();
		});
		// 열 머리는 scope의 그 반 판정을 그대로 쓴다.
		assertThat(response.concepts().get(0).groupShortfall()).isTrue();
		assertThat(response.navigation().classrooms()).hasSize(1);
	}

	@Test
	void traineeHeatmapKeepsRawLevelAndOmitsReviewOnlyFields() {
		UUID traineeId = UUID.randomUUID();
		stubConceptsAndClassrooms();
		when(repository.findParticipatingTeams(managerId, cohortId, projectId, roundId, classroomId))
				.thenReturn(List.of(new ManagerAnalyticsRepository.TeamParticipant(teamId, "2팀", 3)));
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"TRAINEE", "INITIAL", classroomId, teamId)).thenReturn(List.of(
				cell(traineeId, "김민준", jwtId, "3")));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.TRAINEE, ManagerHeatmapResponse.AttemptView.INITIAL,
				classroomId, teamId);

		assertThat(response.scope()).isEqualTo(
				new ManagerHeatmapResponse.Scope(classroomId, "C반", teamId, "2팀"));
		assertThat(response.rows()).singleElement().satisfies(row -> {
			// 개인은 원값이라 평균으로 흐리지 않는다.
			assertThat(row.cells().get(0).value()).isEqualByComparingTo("3");
			assertThat(row.memberCount()).isNull();
			// INITIAL에서는 다시 보기 전용 필드가 비어 키 자체가 빠진다.
			assertThat(row.cells().get(0).initialLevel()).isNull();
			assertThat(row.cells().get(0).delta()).isNull();
		});
	}

	@Test
	void reviewHeatmapCarriesComparisonFields() {
		UUID traineeId = UUID.randomUUID();
		stubConceptsAndClassrooms();
		when(repository.findParticipatingTeams(managerId, cohortId, projectId, roundId, classroomId))
				.thenReturn(List.of(new ManagerAnalyticsRepository.TeamParticipant(teamId, "2팀", 3)));
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"TRAINEE", "REVIEW", classroomId, teamId)).thenReturn(List.of(
				new ManagerAnalyticsRepository.HeatmapCell(traineeId, "김민준", jwtId,
						new BigDecimal("3"), "VALID", null, null, null, null, 1, 3, 2, asOf)));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.TRAINEE, ManagerHeatmapResponse.AttemptView.REVIEW,
				classroomId, teamId);

		assertThat(response.rows().get(0).cells().get(0)).satisfies(cell -> {
			assertThat(cell.initialLevel()).isEqualTo(1);
			assertThat(cell.comparisonLevel()).isEqualTo(3);
			assertThat(cell.delta()).isEqualTo(2);
		});
	}

	@Test
	void summaryRowCarriesRosterMemberCount() {
		stubConceptsAndClassrooms();
		when(repository.findSummary(managerId, cohortId, projectId, roundId, null, null))
				.thenReturn(List.of(cell(null, null, jwtId, "2.4")));
		when(repository.countMembers(managerId, cohortId, projectId, roundId, null, null)).thenReturn(21);

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.CLASS, ManagerHeatmapResponse.AttemptView.INITIAL, null, null);

		assertThat(response.summary()).isNotNull();
		// 명부 인원이라 유효 응시자 수와 다르다.
		assertThat(response.summary().memberCount()).isEqualTo(21);
		assertThat(response.summary().rowId()).isNull();
		assertThat(response.summary().cells().get(0).value()).isEqualByComparingTo("2.4");
	}

	/**
	 * 34차 R2 — 결과가 하나도 없는 사람이 세 카운터 어디에도 안 잡히던 것.
	 *
	 * <p>명부 21명 · 유효 8명인데 미응시·무효·중단이 전부 0이면 화면이 차이를 설명할 수 없다.
	 */
	@Test
	void summaryCountsResultlessTraineesAndExposesTheRemainder() {
		stubConceptsAndClassrooms();
		when(repository.findSummary(managerId, cohortId, projectId, roundId, null, null))
				.thenReturn(List.of(cell(null, null, jwtId, "2.4")));
		when(repository.findUnresolved(managerId, cohortId, projectId, roundId, "CLASS", null, null))
				.thenReturn(List.of(new ManagerAnalyticsRepository.UnresolvedGroup(
						classroomId, "C반", 1, 0, 0, 0)));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.CLASS, ManagerHeatmapResponse.AttemptView.INITIAL, null, null);

		assertThat(response.summary().cells().get(0)).satisfies(cell -> {
			assertThat(cell.validCount()).isEqualTo(8);
			// 격자에 자리가 없던 1명이 미응시로 잡힌다.
			assertThat(cell.notAttendedCount()).isEqualTo(1);
			// 21 − 8(유효) − 1(미응시) = 12. 이 자리가 없어서 차이를 설명하지 못했다.
			assertThat(cell.notInRoundCount()).isEqualTo(12);
		});
	}

	/** 34차 R3 — 팀원 전원이 분석 실패라 팀 행 자체가 사라지던 것. */
	@Test
	void teamWithNoResultsStillGetsRow() {
		UUID missingTeamId = UUID.randomUUID();
		stubConceptsAndClassrooms();
		when(repository.findParticipatingTeams(managerId, cohortId, projectId, roundId, classroomId))
				.thenReturn(List.of(
						new ManagerAnalyticsRepository.TeamParticipant(teamId, "2팀", 3),
						new ManagerAnalyticsRepository.TeamParticipant(missingTeamId, "4팀", 4)));
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"TEAM", "INITIAL", classroomId, null)).thenReturn(List.of(cell(teamId, "2팀", jwtId, "1.7")));
		when(repository.findUnresolved(managerId, cohortId, projectId, roundId, "TEAM", classroomId, null))
				.thenReturn(List.of(new ManagerAnalyticsRepository.UnresolvedGroup(
						missingTeamId, "4팀", 0, 0, 4, 0)));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.TEAM, ManagerHeatmapResponse.AttemptView.INITIAL, classroomId, null);

		// 되살린 행이 이름 순 제자리에 들어간다 — 2팀 다음이 4팀이다.
		assertThat(response.rows()).extracting(ManagerHeatmapResponse.Row::rowName)
				.containsExactly("2팀", "4팀");
		assertThat(response.rows().get(1)).satisfies(row -> {
			assertThat(row.memberCount()).isEqualTo(4);
			// 개념 축만큼 열이 서고, 표본이 없으니 값은 비운다.
			assertThat(row.cells()).hasSize(2);
			assertThat(row.cells().get(0).value()).isNull();
			assertThat(row.cells().get(0).status()).isEqualTo("NO_VALID_RESULT");
			assertThat(row.cells().get(0).interruptedCount()).isEqualTo(4);
		});
	}

	/** 개인 행은 카운터가 아니라 그 사람의 상태를 싣는다 — 화면이 「미응시」를 그대로 그린다. */
	@Test
	void traineeWithNoResultsCarriesOwnStatus() {
		UUID gradedId = UUID.randomUUID();
		UUID absentId = UUID.randomUUID();
		stubConceptsAndClassrooms();
		when(repository.findParticipatingTeams(managerId, cohortId, projectId, roundId, classroomId))
				.thenReturn(List.of(new ManagerAnalyticsRepository.TeamParticipant(teamId, "2팀", 5)));
		when(repository.findHeatmap(managerId, cohortId, projectId, roundId,
				"TRAINEE", "INITIAL", classroomId, teamId)).thenReturn(List.of(
				cell(gradedId, "김민준", jwtId, "3")));
		when(repository.findUnresolved(managerId, cohortId, projectId, roundId, "TRAINEE", classroomId, teamId))
				.thenReturn(List.of(new ManagerAnalyticsRepository.UnresolvedGroup(
						absentId, "한유진", 1, 0, 0, 0)));

		var response = service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.TRAINEE, ManagerHeatmapResponse.AttemptView.INITIAL,
				classroomId, teamId);

		assertThat(response.rows()).extracting(ManagerHeatmapResponse.Row::rowName)
				.containsExactly("김민준", "한유진");
		assertThat(response.rows().get(1)).satisfies(row -> {
			assertThat(row.memberCount()).isNull();
			assertThat(row.cells().get(0).value()).isNull();
			assertThat(row.cells().get(0).status()).isEqualTo("NOT_ATTENDED");
			// 개인 셀은 애초에 카운터가 없다.
			assertThat(row.cells().get(0).notAttendedCount()).isNull();
		});
	}

	/** REVIEW는 「안 친 사람」이 정상이라 결과 없는 인원을 세지 않는다. */
	@Test
	void reviewHeatmapDoesNotCountResultlessTrainees() {
		stubConceptsAndClassrooms();
		when(repository.findParticipatingTeams(managerId, cohortId, projectId, roundId, classroomId))
				.thenReturn(List.of(new ManagerAnalyticsRepository.TeamParticipant(teamId, "2팀", 3)));

		service.findHeatmap("manager@example.com", cohortId, projectId, roundId,
				ManagerHeatmapResponse.Level.TRAINEE, ManagerHeatmapResponse.AttemptView.REVIEW,
				classroomId, teamId);

		verify(repository, never()).findUnresolved(any(), any(), any(), any(), any(), any(), any());
	}

	private void stubConceptsAndClassrooms() {
		when(repository.findConcepts(managerId, cohortId, projectId, roundId)).thenReturn(List.of(
				new ManagerAnalyticsRepository.ConceptAxis(jwtId, "JWT 인증·인가"),
				new ManagerAnalyticsRepository.ConceptAxis(jpaId, "JPA 엔티티 관계 설정")));
		when(repository.findParticipatingClassrooms(managerId, cohortId, projectId, roundId)).thenReturn(
				List.of(new ManagerAnalyticsRepository.ClassParticipant(classroomId, "C반", 26)));
		lenient().when(repository.countMembers(eq(managerId), eq(cohortId), eq(projectId), eq(roundId),
				any(), any())).thenReturn(21);
	}

	private ManagerAnalyticsRepository.HeatmapCell cell(
			UUID rowId, String rowName, UUID teachesId, String value) {
		return new ManagerAnalyticsRepository.HeatmapCell(rowId, rowName, teachesId,
				new BigDecimal(value), "COMPLETE", 8, 0, 0, 0, null, null, null, asOf);
	}
}
