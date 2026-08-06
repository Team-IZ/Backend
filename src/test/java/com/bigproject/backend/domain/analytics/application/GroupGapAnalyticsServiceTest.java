package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.OperationalActionQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.presentation.dto.GroupGapResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupGapAnalyticsServiceTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final RiskTraineeQueryRepository riskTraineeQueryRepository = mock(RiskTraineeQueryRepository.class);
	private final OperationalActionQueryRepository operationalActionQueryRepository =
			mock(OperationalActionQueryRepository.class);
	private final GroupGapAnalyticsService service = new GroupGapAnalyticsService(
			new AnalyticsActorGuard(authUserRepository),
			riskTraineeQueryRepository,
			operationalActionQueryRepository
	);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();
	private final UUID teachesId = UUID.randomUUID();
	private final OperationalActionQueryRepository.RoundRef round =
			new OperationalActionQueryRepository.RoundRef(
					UUID.randomUUID(), 2, "Graph 실습", UUID.randomUUID(), "미프 2차");

	@BeforeEach
	void givenOperator() {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(), organizationId, ACTOR_EMAIL, "Actor", "hash",
				"ACTIVE", true, null, Role.OPERATOR, "ACTIVE")));
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, organizationId)));
	}

	@Test
	void returnsOnlyCombinationsPastTheThresholdAndReportsTheRate() {
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				gap("C반", "Graph 구성", 14, 25),
				gap("A반", "REST 설계", 10, 25)
		));

		GroupGapResponse response = service.findGroupGaps(cohortId, ACTOR_EMAIL);

		assertThat(response.gaps()).hasSize(1);
		GroupGapResponse.GroupGapRow row = response.gaps().get(0);
		assertThat(row.className()).isEqualTo("C반");
		assertThat(row.lowLevelRate()).isEqualByComparingTo(new BigDecimal("0.5600"));
		assertThat(response.emptyReasonCode()).isNull();
	}

	@Test
	void countsEveryEvaluatedCombinationNotOnlyTheFailingOnes() {
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				gap("C반", "Graph 구성", 14, 25),
				gap("A반", "REST 설계", 10, 25),
				gap("B반", "트랜잭션", 3, 25)
		));

		GroupGapResponse response = service.findGroupGaps(cohortId, ACTOR_EMAIL);

		assertThat(response.evaluatedClassConceptCount()).isEqualTo(3);
		assertThat(response.gaps()).hasSize(1);
	}

	@Test
	void separatesNothingEvaluatedFromNothingFailing() {
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of());

		assertThat(service.findGroupGaps(cohortId, ACTOR_EMAIL).emptyReasonCode())
				.isEqualTo(GroupGapResponse.GroupGapEmptyReason.NO_ELIGIBLE_PARTICIPANT);

		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				gap("A반", "REST 설계", 3, 25)
		));

		assertThat(service.findGroupGaps(cohortId, ACTOR_EMAIL).emptyReasonCode())
				.isEqualTo(GroupGapResponse.GroupGapEmptyReason.NO_GROUP_UNDERPERFORMANCE);
	}

	@Test
	void skipsClassesWithNoEligibleMemberInsteadOfCallingThemUnderperforming() {
		// 분모가 0이면 비율이 0%가 아니라 값 없음이라 미달로 올리지 않는다.
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				gap("I반", "Graph 구성", 0, 0)
		));

		GroupGapResponse response = service.findGroupGaps(cohortId, ACTOR_EMAIL);

		assertThat(response.gaps()).isEmpty();
		assertThat(response.emptyReasonCode())
				.isEqualTo(GroupGapResponse.GroupGapEmptyReason.NO_GROUP_UNDERPERFORMANCE);
	}

	@Test
	void publishesTheThresholdSoTheClientNeedNotGuess() {
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of());

		GroupGapResponse response = service.findGroupGaps(cohortId, ACTOR_EMAIL);

		assertThat(response.underperformanceThresholdRatio()).isEqualByComparingTo(new BigDecimal("0.5"));
		assertThat(response.lowLevelMaxStage()).isEqualTo(2);
	}

	@Test
	void rejectsCohortOwnedByAnotherOrganization() {
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, UUID.randomUUID())));

		assertThatThrownBy(() -> service.findGroupGaps(cohortId, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	private OperationalActionQueryRepository.GroupGapRow gap(
			String className, String conceptName, long lowLevelCount, long classMemberCount
	) {
		return new OperationalActionQueryRepository.GroupGapRow(
				round, classId, className, teachesId, conceptName, lowLevelCount, classMemberCount);
	}
}
