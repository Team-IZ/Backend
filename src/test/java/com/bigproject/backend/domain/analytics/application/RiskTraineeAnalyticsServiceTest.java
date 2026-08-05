package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
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
			new RiskTraineeAnalyticsService(authUserRepository, riskTraineeQueryRepository);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();

	@Test
	void dividesRiskCountByEligibleCountExcludingEveryUnaggregatedTrainee() {
		givenOperator();
		givenCompletedRound();
		// 반 인원 25명 중 미응시 2명·중단 1명·무효 응시 3명을 뺀 19명이 분모, 위험자는 6명.
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RiskCellRow(roundId, classId, 19, 6, 2, 1, 3)
		));
		givenRoster(25, 0);

		RiskTraineeRateResponse response = findRates();

		RiskTraineeRateResponse.RiskCell classCell = response.classes().get(0).cells().get(0);
		assertThat(classCell.eligibleCount()).isEqualTo(19);
		assertThat(classCell.riskCount()).isEqualTo(6);
		assertThat(classCell.riskRate()).isEqualByComparingTo(new BigDecimal("0.3158"));
		assertThat(classCell.exclusion().notAttendedCount()).isEqualTo(2);
		assertThat(classCell.exclusion().sessionIncompleteCount()).isEqualTo(1);
	}

	@Test
	void countsEveryExclusionOutsideDenominator() {
		givenOperator();
		givenCompletedRound();
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
		givenCompletedRound();
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
	void returnsNullRateForRoundsThatAreNotAggregatedYet() {
		givenOperator();
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RoundRow(roundId, 4, "RAG 파이프라인", UUID.randomUUID(), "미니프로젝트", "OPEN")
		));
		when(riskTraineeQueryRepository.aggregateRiskCells(any())).thenReturn(List.of());
		givenRoster(25, 0);

		RiskTraineeRateResponse.RiskCell cell = findRates().cohortSummary().cells().get(0);

		assertThat(cell.aggregationStatus()).isEqualTo(RoundAggregationStatus.NOT_AGGREGATED);
		assertThat(cell.riskRate()).isNull();
	}

	@Test
	void returnsNullRateWhenEveryTraineeWasExcluded() {
		givenOperator();
		givenCompletedRound();
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

		assertThatThrownBy(() -> service.findRiskTraineeRates(cohortId, null, 4, 2, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		verify(riskTraineeQueryRepository, never()).aggregateRiskCells(any());
	}

	private RiskTraineeRateResponse findRates() {
		return service.findRiskTraineeRates(cohortId, null, null, null, ACTOR_EMAIL);
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

	private void givenCompletedRound() {
		when(riskTraineeQueryRepository.findRounds(any())).thenReturn(List.of(
				new RiskTraineeQueryRepository.RoundRow(roundId, 1, "K8s 배포 실습", UUID.randomUUID(), "미니프로젝트", "COMPLETED")
		));
	}

	private void givenRoster(long traineeCount, long withdrawnCount) {
		when(riskTraineeQueryRepository.findCohortRoster(cohortId, organizationId))
				.thenReturn(new RiskTraineeQueryRepository.RosterCount(traineeCount, withdrawnCount));
		when(riskTraineeQueryRepository.findClassRosters(cohortId, organizationId)).thenReturn(List.of(
				new RiskTraineeQueryRepository.ClassRosterRow(classId, "C반", traineeCount, withdrawnCount)
		));
	}
}
