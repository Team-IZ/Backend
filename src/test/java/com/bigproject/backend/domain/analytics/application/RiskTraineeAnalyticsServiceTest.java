package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.domain.RoundAggregationStatus;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskTraineeAnalyticsServiceTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final RiskTraineeQueryRepository riskTraineeQueryRepository = mock(RiskTraineeQueryRepository.class);
	private final RiskTraineeAnalyticsService service =
			new RiskTraineeAnalyticsService(
					new AnalyticsActorGuard(authUserRepository), riskTraineeQueryRepository);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID laterRoundId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();
	private final UUID otherClassId = UUID.randomUUID();

	@Test
	void dividesRiskCountByEligibleCountExcludingEveryUnaggregatedTrainee() {
		givenOperator();
		givenPublishedRound();
		// 반 인원 25명 중 미응시 2명·중단 1명·무효 응시 3명을 뺀 19명이 분모, 위험자는 6명.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse.RiskCell classCell = findRates().classes().get(0).cells().get(0);

		assertThat(classCell.eligibleCount()).isEqualTo(19);
		assertThat(classCell.riskCount()).isEqualTo(6);
		assertThat(classCell.riskRate()).isEqualByComparingTo(new BigDecimal("0.3158"));
		assertThat(classCell.exclusion().notAttendedCount()).isEqualTo(2);
		assertThat(classCell.exclusion().sessionIncompleteCount()).isEqualTo(1);
	}

	@Test
	void countsEveryExclusionOutsideDenominator() {
		givenOperator();
		givenPublishedRound();
		// 미집계 3종은 모두 분모 밖이므로 분모와 미집계를 더하면 회차 수행 대상자 전원이 된다.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse.RiskCell cell = findRates().classes().get(0).cells().get(0);

		assertThat(cell.exclusion().invalidAttemptCount()).isEqualTo(3);
		assertThat(cell.eligibleCount()
				+ cell.exclusion().notAttendedCount()
				+ cell.exclusion().sessionIncompleteCount()
				+ cell.exclusion().invalidAttemptCount())
				.isEqualTo(25);
	}

	@Test
	void addsUnassignedTraineesToCohortRowOnly() {
		givenOperator();
		givenPublishedRound();
		// 회차 시점에 반 배정이 없던 교육생(class_id = null)은 기수 전체에만 들어간다.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3),
				new RiskTraineeQueryRepository.RiskCellRow(roundId, null, 3, 3, 0, 0, 0)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse response = findRates();

		assertThat(response.cohortSummary().cells().get(0).eligibleCount()).isEqualTo(22);
		assertThat(response.cohortSummary().cells().get(0).riskCount()).isEqualTo(9);
		assertThat(response.classes().get(0).cells().get(0).eligibleCount()).isEqualTo(19);
	}

	@Test
	void treatsRoundWithoutPublishedReportAsNotAggregated() {
		givenOperator();
		// 회차가 COMPLETED로 끝났어도 발행된 리포트가 없으면 값을 읽을 수 없다.
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "COMPLETED", false)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 0, 0, 0)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse.RiskCell cell = findRates().cohortSummary().cells().get(0);

		assertThat(cell.aggregationStatus()).isEqualTo(RoundAggregationStatus.NOT_AGGREGATED);
		assertThat(cell.riskRate()).isNull();
	}

	@Test
	void readsRateOnceReportIsPublishedEvenBeforeRoundCompletes() {
		givenOperator();
		// 반대로 회차가 아직 CLOSED여도 발행본이 있으면 읽는다. 판정 기준은 발행 여부다.
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "CLOSED", true)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 20, 5, 0, 0, 0)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse.RiskCell cell = findRates().cohortSummary().cells().get(0);

		assertThat(cell.aggregationStatus()).isEqualTo(RoundAggregationStatus.AGGREGATED);
		assertThat(cell.riskRate()).isEqualByComparingTo(new BigDecimal("0.2500"));
	}

	@Test
	void keepsPlannedRoundsSeparateFromUnpublishedOnes() {
		givenOperator();
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "PLANNED", false)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of());
		givenRoster(25, 0);

		assertThat(findRates().cohortSummary().cells().get(0).aggregationStatus())
				.isEqualTo(RoundAggregationStatus.NOT_STARTED);
	}

	@Test
	void marksClassAboveCohortRateAsWorseAndBelowAsBetter() {
		givenOperator();
		givenPublishedRound();
		// C반 6/20 = 30%, D반 2/20 = 10%, 기수 전체 8/40 = 20%.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 20, 6, 0, 0, 0),
				new RiskTraineeQueryRepository.RiskCellRow(roundId, otherClassId, 20, 2, 0, 0, 0)
		));
		givenTwoClassRoster();

		RiskTraineeRateResponse response = findRates(RiskTraineeSort.NAME);

		assertThat(response.cohortSummary().cells().get(0).comparisonToCohort()).isNull();
		assertThat(cellOf(response, "C반").comparisonToCohort()).isEqualTo(CohortRiskComparison.WORSE);
		assertThat(cellOf(response, "D반").comparisonToCohort()).isEqualTo(CohortRiskComparison.BETTER);
	}

	@Test
	void marksClassMatchingCohortRateAsSame() {
		givenOperator();
		givenPublishedRound();
		// 두 반이 같은 비율이면 기수 전체도 같은 비율이라 둘 다 SAME이다.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 20, 4, 0, 0, 0),
				new RiskTraineeQueryRepository.RiskCellRow(roundId, otherClassId, 20, 4, 0, 0, 0)
		));
		givenTwoClassRoster();

		RiskTraineeRateResponse response = findRates(RiskTraineeSort.NAME);

		assertThat(cellOf(response, "C반").comparisonToCohort()).isEqualTo(CohortRiskComparison.SAME);
		assertThat(cellOf(response, "D반").comparisonToCohort()).isEqualTo(CohortRiskComparison.SAME);
	}

	@Test
	void leavesComparisonUnsetWhileRoundIsNotAggregated() {
		givenOperator();
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "CLOSED", false)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 20, 6, 0, 0, 0)
		));
		givenRoster(25, 0);

		assertThat(findRates().classes().get(0).cells().get(0).comparisonToCohort()).isNull();
	}

	@Test
	void rollsUpExclusionsFromTheMostRecentPublishedRound() {
		givenOperator();
		// 1차는 발행됐고 2차는 미발행이므로 롤업 기준은 1차다.
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "COMPLETED", true),
				round(laterRoundId, 2, "CLOSED", false)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3),
				new RiskTraineeQueryRepository.RiskCellRow(laterRoundId, classId, 24, 4, 1, 0, 0)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse.ExclusionBreakdown rollup = findRates().classes().get(0).exclusionRollup();

		assertThat(rollup.notAttendedCount()).isEqualTo(2);
		assertThat(rollup.sessionIncompleteCount()).isEqualTo(1);
		assertThat(rollup.invalidAttemptCount()).isEqualTo(3);
		assertThat(rollup.total()).isEqualTo(6);
	}

	@Test
	void returnsZeroRollupWhenNoRoundHasBeenPublished() {
		givenOperator();
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "CLOSED", false)
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3)
		));
		givenRoster(25, 0);

		assertThat(findRates().classes().get(0).exclusionRollup().total()).isZero();
	}

	@Test
	void sortsWorstClassAgainstCohortFirstByDefault() {
		givenOperator();
		givenPublishedRound();
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 20, 2, 0, 0, 0),
				new RiskTraineeQueryRepository.RiskCellRow(roundId, otherClassId, 20, 6, 0, 0, 0)
		));
		givenTwoClassRoster();

		RiskTraineeRateResponse response = findRates();

		assertThat(response.appliedSort()).isEqualTo(RiskTraineeSort.RECENT_ROUND_WORST);
		// D반 30% > C반 10% 이므로 D반이 먼저다.
		assertThat(response.classes()).extracting(RiskTraineeRateResponse.ClassRiskSummary::className)
				.containsExactly("D반", "C반");
	}

	@Test
	void sortsByExclusionCountWhenAsked() {
		givenOperator();
		givenPublishedRound();
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3),
				new RiskTraineeQueryRepository.RiskCellRow(roundId, otherClassId, 24, 2, 1, 0, 0)
		));
		givenTwoClassRoster();

		RiskTraineeRateResponse response = findRates(RiskTraineeSort.EXCLUSION_COUNT);

		// C반 미집계 6명 > D반 1명.
		assertThat(response.classes()).extracting(RiskTraineeRateResponse.ClassRiskSummary::className)
				.containsExactly("C반", "D반");
	}

	@Test
	void sortsByWorseRoundCountWhenAsked() {
		givenOperator();
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "COMPLETED", true),
				round(laterRoundId, 2, "COMPLETED", true)
		));
		// C반은 1차만, D반은 두 회차 모두 기수 전체보다 나쁘다.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 20, 6, 0, 0, 0),
				new RiskTraineeQueryRepository.RiskCellRow(roundId, otherClassId, 20, 7, 0, 0, 0),
				new RiskTraineeQueryRepository.RiskCellRow(laterRoundId, classId, 20, 2, 0, 0, 0),
				new RiskTraineeQueryRepository.RiskCellRow(laterRoundId, otherClassId, 20, 8, 0, 0, 0)
		));
		givenTwoClassRoster();

		RiskTraineeRateResponse response = findRates(RiskTraineeSort.WORSE_ROUND_COUNT);

		assertThat(response.classes()).extracting(RiskTraineeRateResponse.ClassRiskSummary::className)
				.containsExactly("D반", "C반");
	}

	@Test
	void reportsRegisteredRoundCountBeforeRangeFilter() {
		givenOperator();
		givenPublishedRound();
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of());
		when(riskTraineeQueryRepository.countRegisteredRounds(any())).thenReturn(6);
		givenRoster(25, 0);

		RiskTraineeRateResponse response =
				service.findRiskTraineeRates(cohortId, null, null, 1, 3, null, ACTOR_EMAIL);

		assertThat(response.totalRegisteredRoundCount()).isEqualTo(6);
		assertThat(response.rounds()).hasSize(1);
	}

	@Test
	void narrowsToASingleProjectWhenProjectIdIsGiven() {
		givenOperator();
		givenPublishedRound();
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of());
		when(riskTraineeQueryRepository.projectBelongsToCohort(projectId, cohortId, organizationId, "MINI_PROJECT"))
				.thenReturn(true);
		givenRoster(25, 0);

		RiskTraineeRateResponse response =
				service.findRiskTraineeRates(cohortId, projectId, null, null, null, null, ACTOR_EMAIL);

		assertThat(response.projectId()).isEqualTo(projectId);
	}

	@Test
	void rejectsProjectOutsideTheCohort() {
		givenOperator();
		when(riskTraineeQueryRepository.projectBelongsToCohort(any(), any(), any(), any())).thenReturn(false);

		assertThatThrownBy(() ->
				service.findRiskTraineeRates(cohortId, projectId, null, null, null, null, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		verify(riskTraineeQueryRepository, never()).aggregateRiskCells(any());
	}

	@Test
	void returnsNullRateWhenEveryTraineeWasExcluded() {
		givenOperator();
		givenPublishedRound();
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 0, 0, 3, 2, 0)
		));
		givenRoster(5, 0);

		assertThat(findRates().classes().get(0).cells().get(0).riskRate()).isNull();
	}

	@Test
	void rejectsCohortOwnedByAnotherOrganization() {
		givenOperator();
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, UUID.randomUUID())));

		assertThatThrownBy(this::findRates)
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(riskTraineeQueryRepository, never()).aggregateRiskCells(any());
	}

	@Test
	void rejectsInvertedRoundRange() {
		givenOperator();
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, organizationId)));

		assertThatThrownBy(() ->
				service.findRiskTraineeRates(cohortId, null, null, 4, 2, null, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		verify(riskTraineeQueryRepository, never()).aggregateRiskCells(any());
	}

	private RiskTraineeRateResponse findRates() {
		return findRates(null);
	}

	private RiskTraineeRateResponse findRates(RiskTraineeSort sort) {
		return service.findRiskTraineeRates(cohortId, null, null, null, null, sort, ACTOR_EMAIL);
	}

	private RiskTraineeRateResponse.RiskCell cellOf(RiskTraineeRateResponse response, String className) {
		return response.classes().stream()
				.filter(summary -> className.equals(summary.className()))
				.findFirst()
				.orElseThrow()
				.cells()
				.get(0);
	}

	private RiskTraineeQueryRepository.RoundRow round(
			UUID assessmentRoundId,
			int roundNo,
			String status,
			boolean reportPublished
	) {
		return new RiskTraineeQueryRepository.RoundRow(
				assessmentRoundId, roundNo, "K8s 배포 실습", projectId, "미니프로젝트", status, reportPublished);
	}

	private void givenOperator() {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(),
				organizationId,
				ACTOR_EMAIL,
				"Actor",
				"hash",
				"ACTIVE",
				true,
				null,
				Role.OPERATOR,
				"ACTIVE"
		)));
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, organizationId)));
	}

	private void givenPublishedRound() {
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				round(roundId, 1, "COMPLETED", true)
		));
	}

	private void givenRoster(long traineeCount, long withdrawnCount) {
		when(riskTraineeQueryRepository.findCohortRoster(cohortId, organizationId))
				.thenReturn(new RiskTraineeQueryRepository.RosterCount(traineeCount, withdrawnCount));
		when(riskTraineeQueryRepository.findClassRosters(cohortId, organizationId)).thenReturn(List.of(
				new RiskTraineeQueryRepository.ClassRosterRow(classId, "C반", traineeCount, withdrawnCount)
		));
	}

	private void givenTwoClassRoster() {
		when(riskTraineeQueryRepository.findCohortRoster(cohortId, organizationId))
				.thenReturn(new RiskTraineeQueryRepository.RosterCount(50, 0));
		when(riskTraineeQueryRepository.findClassRosters(cohortId, organizationId)).thenReturn(List.of(
				new RiskTraineeQueryRepository.ClassRosterRow(classId, "C반", 25, 0),
				new RiskTraineeQueryRepository.ClassRosterRow(otherClassId, "D반", 25, 0)
		));
	}
}
