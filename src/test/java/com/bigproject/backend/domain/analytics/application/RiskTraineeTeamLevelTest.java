package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeLevel;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskTraineeTeamLevelTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final RiskTraineeQueryRepository riskTraineeQueryRepository = mock(RiskTraineeQueryRepository.class);
	private final RiskTraineeAnalyticsService service = new RiskTraineeAnalyticsService(
			new AnalyticsActorGuard(authUserRepository), riskTraineeQueryRepository);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID teamA = UUID.randomUUID();
	private final UUID teamB = UUID.randomUUID();

	@BeforeEach
	void givenOperatorAndPublishedRound() {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(), organizationId, ACTOR_EMAIL, "Actor", "hash",
				"ACTIVE", true, null, Role.OPERATOR, "ACTIVE")));
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, organizationId)));
		when(riskTraineeQueryRepository.classroomBelongsToCohort(classId, cohortId, organizationId))
				.thenReturn(true);
		when(riskTraineeQueryRepository.projectBelongsToCohort(projectId, cohortId, organizationId, "MINI_PROJECT"))
				.thenReturn(true);
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				// roundNo(프로젝트 안 응시 번호) · cohortRoundNo(기수 안 회차 순번) — 12차 R1
				new RiskTraineeQueryRepository.RoundRow(
						roundId, 1, 1, "K8s 배포 실습", projectId, "미프 1차", "COMPLETED", true)
		));
		when(riskTraineeQueryRepository.findCohortRoster(cohortId, organizationId))
				.thenReturn(new RiskTraineeQueryRepository.RosterCount(50, 0));
	}

	@Test
	void requiresAProjectBecauseTeamsDoNotCarryAcrossThem() {
		assertThatThrownBy(() -> findTeamRates(null, List.of(classId), null))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.TEAM_LEVEL_PROJECT_REQUIRED));
		verify(riskTraineeQueryRepository, never()).aggregateTeamRiskCells(any(), any());
	}

	@Test
	void requiresExactlyOneClassBecauseTeamNumbersRepeatAcrossClasses() {
		assertThatThrownBy(() -> findTeamRates(projectId, null, null))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.TEAM_LEVEL_SINGLE_CLASSROOM_REQUIRED));

		when(riskTraineeQueryRepository.classroomBelongsToCohort(any(), any(), any())).thenReturn(true);
		assertThatThrownBy(() -> findTeamRates(projectId, List.of(classId, UUID.randomUUID()), null))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.TEAM_LEVEL_SINGLE_CLASSROOM_REQUIRED));
		verify(riskTraineeQueryRepository, never()).aggregateTeamRiskCells(any(), any());
	}

	@Test
	void buildsTeamRowsAndIncludesTheSelectedClassAsTheComparisonBaseline() {
		givenTeamRoster();
		when(riskTraineeQueryRepository.aggregateTeamRiskCells(any(), eq(classId))).thenReturn(List.of(
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamA, 5, 2, 0, 0, 0)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 25, 5, 0, 0, 0)
		));

		RiskTraineeRateResponse response = findTeamRates(projectId, List.of(classId), null);

		assertThat(response.level()).isEqualTo(RiskTraineeLevel.TEAM);
		// classes에는 팀 비교 기준이 되는 선택된 반(C반) 1건만 담긴다.
		assertThat(response.classes()).hasSize(1);
		assertThat(response.classes().get(0).className()).isEqualTo("C반");
		assertThat(response.teams()).hasSize(2);
		assertThat(response.cohortSummary().cells().get(0).riskRate())
				.isEqualByComparingTo(new BigDecimal("0.2000"));
	}

	@Test
	void keepsTeamsThatHaveNoResultYetAsEmptyRows() {
		givenTeamRoster();
		// 1팀만 결과가 있다. 2팀이 사라지면 '팀이 없는 것'과 '결과가 없는 것'이 섞인다.
		when(riskTraineeQueryRepository.aggregateTeamRiskCells(any(), eq(classId))).thenReturn(List.of(
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamA, 5, 2, 0, 0, 0)
		));

		RiskTraineeRateResponse response = findTeamRates(projectId, List.of(classId), RiskTraineeSort.NAME);

		RiskTraineeRateResponse.TeamRiskSummary second = response.teams().get(1);
		assertThat(second.teamNumber()).isEqualTo("2");
		assertThat(second.cells()).hasSize(1);
		assertThat(second.cells().get(0).eligibleCount()).isZero();
		assertThat(second.cells().get(0).riskRate()).isNull();
	}

	@Test
	void comparesEachTeamAgainstItsOwnClassRateNotTheCohortRate() {
		givenTeamRoster();
		// 반(C반)에 이 반 데이터만 있어 반 비율도 기수 비율과 같은 5/25 = 20%다.
		// 1팀 2/5 = 40% 는 나쁨, 2팀 0/5 = 0% 는 좋음 — 기준이 C반이라도 값은 같다.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 25, 5, 0, 0, 0)
		));
		when(riskTraineeQueryRepository.aggregateTeamRiskCells(any(), eq(classId))).thenReturn(List.of(
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamA, 5, 2, 0, 0, 0),
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamB, 5, 0, 0, 0, 0)
		));

		RiskTraineeRateResponse response = findTeamRates(projectId, List.of(classId), RiskTraineeSort.NAME);

		assertThat(response.teams().get(0).cells().get(0).comparisonToCohort())
				.isEqualTo(CohortRiskComparison.WORSE);
		assertThat(response.teams().get(1).cells().get(0).comparisonToCohort())
				.isEqualTo(CohortRiskComparison.BETTER);
	}

	@Test
	void dropsTraineesWithoutATeamInsteadOfInventingARow() {
		givenTeamRoster();
		when(riskTraineeQueryRepository.aggregateTeamRiskCells(any(), eq(classId))).thenReturn(List.of(
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamA, 5, 2, 0, 0, 0),
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, null, 3, 3, 0, 0, 0)
		));

		RiskTraineeRateResponse response = findTeamRates(projectId, List.of(classId), RiskTraineeSort.NAME);

		assertThat(response.teams()).hasSize(2);
		assertThat(response.teams()).allSatisfy(team -> assertThat(team.teamId()).isNotNull());
	}

	@Test
	void sortsTeamRowsWithTheSameRulesAsClassRows() {
		givenTeamRoster();
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 25, 5, 0, 0, 0)
		));
		when(riskTraineeQueryRepository.aggregateTeamRiskCells(any(), eq(classId))).thenReturn(List.of(
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamA, 5, 0, 0, 0, 0),
				new RiskTraineeQueryRepository.TeamRiskCellRow(roundId, teamB, 5, 3, 0, 0, 0)
		));

		RiskTraineeRateResponse response =
				findTeamRates(projectId, List.of(classId), RiskTraineeSort.RECENT_ROUND_WORST);

		// 2팀 60% 가 1팀 0% 보다 먼저다.
		assertThat(response.teams()).extracting(RiskTraineeRateResponse.TeamRiskSummary::teamNumber)
				.containsExactly("2", "1");
	}

	private void givenTeamRoster() {
		when(riskTraineeQueryRepository.findTeamRosters(projectId, classId, organizationId)).thenReturn(List.of(
				new RiskTraineeQueryRepository.TeamRosterRow(teamA, "1", "알파", classId, "C반", 5),
				new RiskTraineeQueryRepository.TeamRosterRow(teamB, "2", "브라보", classId, "C반", 5)
		));
		when(riskTraineeQueryRepository.findClassRosters(cohortId, organizationId)).thenReturn(List.of(
				new RiskTraineeQueryRepository.ClassRosterRow(classId, "C반", 25, 0, List.of())
		));
	}

	private RiskTraineeRateResponse findTeamRates(UUID projectId, List<UUID> classroomIds, RiskTraineeSort sort) {
		return service.findRiskTraineeRates(
				cohortId, projectId, classroomIds, null, null, RiskTraineeLevel.TEAM, sort, ACTOR_EMAIL);
	}
}
