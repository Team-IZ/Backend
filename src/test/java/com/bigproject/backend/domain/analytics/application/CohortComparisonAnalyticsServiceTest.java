package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.ChangeDirection;
import com.bigproject.backend.domain.analytics.domain.CohortComparisonQueryRepository;
import com.bigproject.backend.domain.analytics.domain.ComparisonEmptyState;
import com.bigproject.backend.domain.analytics.domain.ComparisonSort;
import com.bigproject.backend.domain.analytics.domain.ConceptPresence;
import com.bigproject.backend.domain.analytics.domain.NotComparableReason;
import com.bigproject.backend.domain.analytics.presentation.dto.CohortComparisonResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CohortComparisonAnalyticsServiceTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final CohortComparisonQueryRepository queryRepository = mock(CohortComparisonQueryRepository.class);
	private final CohortComparisonAnalyticsService service =
			new CohortComparisonAnalyticsService(
					new AnalyticsActorGuard(authUserRepository), queryRepository);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID targetCohortId = UUID.randomUUID();
	private final UUID baselineCohortId = UUID.randomUUID();

	private final List<CohortComparisonQueryRepository.ConceptLevelRow> levels = new ArrayList<>();

	@BeforeEach
	void setUp() {
		givenOperator();
		when(queryRepository.findConceptRounds(any(), anyList())).thenReturn(List.of());
		when(queryRepository.aggregateConceptLevels(any(), anyList())).thenAnswer(invocation -> levels);
	}

	@Test
	void derivesAverageReachedLevelFromDistributionSum() {
		// 24명이 각각 0~4단에 흩어져 도달 단계 합계가 60이면 평균은 2.50단이다.
		givenConcept(targetCohortId, "Graph 구성", "60", 24);
		givenConcept(baselineCohortId, "Graph 구성", "69.6", 24);

		CohortComparisonResponse.ConceptComparison concept = compare().concepts().get(0);

		assertThat(concept.target().averageReachedLevel()).isEqualByComparingTo(new BigDecimal("2.50"));
		assertThat(concept.baseline().averageReachedLevel()).isEqualByComparingTo(new BigDecimal("2.90"));
		assertThat(concept.target().participantCount()).isEqualTo(24);
	}

	@Test
	void reportsNullAverageWhenNobodyWasMeasured() {
		// 응시 인원이 없는 개념은 0단이 아니라 값 없음이다. 0단은 1단도 통과하지 못했다는 측정 결과다.
		givenConcept(targetCohortId, "Graph 구성", "0", 0);
		givenConcept(baselineCohortId, "Graph 구성", "69.6", 24);

		CohortComparisonResponse.ConceptComparison concept = compare().concepts().get(0);

		assertThat(concept.target().averageReachedLevel()).isNull();
		assertThat(concept.target().levelBand()).isNull();
		assertThat(concept.change().direction()).isEqualTo(ChangeDirection.NOT_COMPARABLE);
		assertThat(concept.change().notComparableReasonCode())
				.isEqualTo(NotComparableReason.AGGREGATION_UNAVAILABLE);
	}

	@Test
	void assignsColourBandByRoundingToTheNearestLevel() {
		// 밴드 경계 0.5·1.5·2.5·3.5 는 위쪽 밴드로 올린다.
		assertThat(bandOf("0.49")).isEqualTo(0);
		assertThat(bandOf("0.50")).isEqualTo(1);
		assertThat(bandOf("1.49")).isEqualTo(1);
		assertThat(bandOf("1.50")).isEqualTo(2);
		assertThat(bandOf("2.50")).isEqualTo(3);
		assertThat(bandOf("3.50")).isEqualTo(4);
		assertThat(bandOf("4.00")).isEqualTo(4);
	}

	@Test
	void treatsMinusThreeTenthsAsWorseAndPlusThreeTenthsAsBetter() {
		assertThat(directionOf("2.90", "2.59")).isEqualTo(ChangeDirection.WORSE);
		assertThat(directionOf("2.90", "2.60")).isEqualTo(ChangeDirection.WORSE);
		assertThat(directionOf("2.90", "2.61")).isEqualTo(ChangeDirection.SIMILAR);
		assertThat(directionOf("2.90", "3.19")).isEqualTo(ChangeDirection.SIMILAR);
		assertThat(directionOf("2.90", "3.20")).isEqualTo(ChangeDirection.BETTER);
	}

	@Test
	void marksConceptMissingFromTheBaselineCohortAsNotComparable() {
		// 목업의 'State 관리'처럼 지난 기수에 없던 개념이다.
		givenConcept(targetCohortId, "State 관리", "79.2", 24);
		givenConcept(targetCohortId, "Graph 구성", "60", 24);
		givenConcept(baselineCohortId, "Graph 구성", "69.6", 24);

		CohortComparisonResponse.ConceptComparison concept = conceptNamed(compare(), "State 관리");

		assertThat(concept.baseline().presence()).isEqualTo(ConceptPresence.ABSENT_IN_COHORT);
		assertThat(concept.target().averageReachedLevel()).isEqualByComparingTo(new BigDecimal("3.30"));
		assertThat(concept.change().direction()).isEqualTo(ChangeDirection.NOT_COMPARABLE);
		assertThat(concept.change().delta()).isNull();
		assertThat(concept.change().notComparableReasonCode())
				.isEqualTo(NotComparableReason.ABSENT_IN_BASELINE);
	}

	@Test
	void excludesMergedConceptFromComparison() {
		// 지난 기수의 개념이 다른 개념으로 병합됐으면 두 기수의 뜻이 같다는 전제가 깨진다.
		levels.add(conceptRow(targetCohortId, "Graph 구성", "60", 24, "ACTIVE", "SINGLE_SOURCE", 2));
		levels.add(conceptRow(baselineCohortId, "Graph 구성", "69.6", 24, "MERGED", "SINGLE_SOURCE", 1));
		givenConcept(targetCohortId, "REST 설계", "81.6", 24);
		givenConcept(baselineCohortId, "REST 설계", "79.2", 24);

		CohortComparisonResponse.ConceptComparison concept = conceptNamed(compare(), "Graph 구성");

		assertThat(concept.baseline().presence()).isEqualTo(ConceptPresence.MERGED);
		assertThat(concept.change().direction()).isEqualTo(ChangeDirection.NOT_COMPARABLE);
		assertThat(concept.change().notComparableReasonCode()).isEqualTo(NotComparableReason.CONCEPT_MERGED);
	}

	@Test
	void excludesConceptWhoseAggregationPolicyIsNotSettled() {
		// 반복 개념 산식이 확정되지 않으면(POLICY_REQUIRED) 평균을 그대로 비교할 수 없다.
		levels.add(conceptRow(targetCohortId, "Graph 구성", "60", 24, "ACTIVE", "POLICY_REQUIRED", 2));
		levels.add(conceptRow(baselineCohortId, "Graph 구성", "69.6", 24, "ACTIVE", "SINGLE_SOURCE", 1));
		givenConcept(targetCohortId, "REST 설계", "81.6", 24);
		givenConcept(baselineCohortId, "REST 설계", "79.2", 24);

		CohortComparisonResponse.ConceptComparison concept = conceptNamed(compare(), "Graph 구성");

		assertThat(concept.change().direction()).isEqualTo(ChangeDirection.NOT_COMPARABLE);
		assertThat(concept.change().notComparableReasonCode())
				.isEqualTo(NotComparableReason.AGGREGATION_UNAVAILABLE);
	}

	@Test
	void reproducesTheMockupGridInWorsenedOrder() {
		// 목업 6행을 그대로 고정한다. 24명 기준이라 도달 단계 합계 = 평균 × 24 이다.
		givenConcept(baselineCohortId, "Graph 구성", "69.6", 24);   // 2.9
		givenConcept(targetCohortId, "Graph 구성", "60", 24);       // 2.5
		givenConcept(baselineCohortId, "트랜잭션", "76.8", 24);      // 3.2
		givenConcept(targetCohortId, "트랜잭션", "69.6", 24);        // 2.9
		givenConcept(baselineCohortId, "HITL Trigger", "64.8", 24); // 2.7
		givenConcept(targetCohortId, "HITL Trigger", "67.2", 24);   // 2.8
		givenConcept(baselineCohortId, "REST 설계", "79.2", 24);     // 3.3
		givenConcept(targetCohortId, "REST 설계", "81.6", 24);       // 3.4
		givenConcept(targetCohortId, "State 관리", "79.2", 24);      // 3.3, 6기에 없음
		givenConcept(targetCohortId, "캐시 전략", "81.6", 24);        // 3.4, 6기에 없음

		List<CohortComparisonResponse.ConceptComparison> concepts = compare().concepts();

		assertThat(concepts).extracting(CohortComparisonResponse.ConceptComparison::conceptName)
				.containsExactly("Graph 구성", "트랜잭션", "HITL Trigger", "REST 설계", "State 관리", "캐시 전략");
		assertThat(concepts).extracting(concept -> concept.change().direction()).containsExactly(
				ChangeDirection.WORSE,
				ChangeDirection.WORSE,
				ChangeDirection.SIMILAR,
				ChangeDirection.SIMILAR,
				ChangeDirection.NOT_COMPARABLE,
				ChangeDirection.NOT_COMPARABLE
		);
		assertThat(concepts.get(0).change().delta()).isEqualByComparingTo(new BigDecimal("-0.40"));
		assertThat(concepts.get(1).change().delta()).isEqualByComparingTo(new BigDecimal("-0.30"));
		assertThat(concepts.get(2).change().delta()).isEqualByComparingTo(new BigDecimal("0.10"));
	}

	@Test
	void ordersByImprovementWhenAskedAndKeepsNotComparableRowsLast() {
		givenConcept(baselineCohortId, "Graph 구성", "69.6", 24);   // 2.9 → 2.5, -0.4
		givenConcept(targetCohortId, "Graph 구성", "60", 24);
		givenConcept(baselineCohortId, "REST 설계", "79.2", 24);     // 3.3 → 3.4, +0.1
		givenConcept(targetCohortId, "REST 설계", "81.6", 24);
		givenConcept(targetCohortId, "State 관리", "79.2", 24);      // 비교 대상 아님

		List<CohortComparisonResponse.ConceptComparison> concepts =
				compare(ComparisonSort.IMPROVED).concepts();

		assertThat(concepts).extracting(CohortComparisonResponse.ConceptComparison::conceptName)
				.containsExactly("REST 설계", "Graph 구성", "State 관리");
	}

	@Test
	void ordersByCurriculumPositionWhenSortedByConcept() {
		levels.add(sourcedRow(targetCohortId, "캐시 전략", 5, 71));
		levels.add(sourcedRow(baselineCohortId, "캐시 전략", 5, 71));
		levels.add(sourcedRow(targetCohortId, "REST 설계", 1, 12));
		levels.add(sourcedRow(baselineCohortId, "REST 설계", 1, 12));
		levels.add(sourcedRow(targetCohortId, "Graph 구성", 4, 62));
		levels.add(sourcedRow(baselineCohortId, "Graph 구성", 4, 62));

		List<CohortComparisonResponse.ConceptComparison> concepts =
				compare(ComparisonSort.CONCEPT).concepts();

		assertThat(concepts).extracting(CohortComparisonResponse.ConceptComparison::conceptName)
				.containsExactly("REST 설계", "Graph 구성", "캐시 전략");
	}

	@Test
	void marksCurriculumVersionChangeOnlyWhenBothVersionsAreKnownAndDiffer() {
		levels.add(conceptRow(targetCohortId, "Graph 구성", "60", 24, "ACTIVE", "SINGLE_SOURCE", 2));
		levels.add(conceptRow(baselineCohortId, "Graph 구성", "69.6", 24, "ACTIVE", "SINGLE_SOURCE", 1));
		levels.add(conceptRow(targetCohortId, "REST 설계", "81.6", 24, "ACTIVE", "SINGLE_SOURCE", 3));
		levels.add(conceptRow(baselineCohortId, "REST 설계", "79.2", 24, "ACTIVE", "SINGLE_SOURCE", 3));

		CohortComparisonResponse response = compare();

		CohortComparisonResponse.CurriculumVersionChange changed =
				conceptNamed(response, "Graph 구성").curriculumVersion();
		assertThat(changed.baselineVersionNo()).isEqualTo(1);
		assertThat(changed.targetVersionNo()).isEqualTo(2);
		assertThat(changed.versionChanged()).isTrue();

		assertThat(conceptNamed(response, "REST 설계").curriculumVersion().versionChanged()).isFalse();
	}

	@Test
	void returnsCandidatesOnlyWhenNoBaselineWasChosen() {
		givenCandidates(new CohortComparisonQueryRepository.BaselineCandidateRow(baselineCohortId, "6기", true));

		CohortComparisonResponse response =
				service.findCohortComparison(targetCohortId, null, ComparisonSort.WORSENED, ACTOR_EMAIL);

		assertThat(response.emptyStateCode()).isNull();
		assertThat(response.baselineCohort()).isNull();
		assertThat(response.concepts()).isEmpty();
		assertThat(response.availableBaselineCohorts()).hasSize(1);
		verify(queryRepository, never()).aggregateConceptLevels(any(), anyList());
	}

	@Test
	void reportsFirstCohortOfTheOrganizationAsHavingNothingToCompare() {
		givenCandidates();

		CohortComparisonResponse response =
				service.findCohortComparison(targetCohortId, null, ComparisonSort.WORSENED, ACTOR_EMAIL);

		assertThat(response.emptyStateCode()).isEqualTo(ComparisonEmptyState.NO_COMPARABLE_COHORT);
		assertThat(response.concepts()).isEmpty();
	}

	@Test
	void reportsMissingReportWhenEitherCohortHasNoPublishedSnapshot() {
		givenCandidates(new CohortComparisonQueryRepository.BaselineCandidateRow(baselineCohortId, "6기", false));

		CohortComparisonResponse response = compare();

		assertThat(response.emptyStateCode()).isEqualTo(ComparisonEmptyState.REPORT_NOT_PUBLISHED);
		assertThat(response.concepts()).isEmpty();
		verify(queryRepository, never()).aggregateConceptLevels(any(), anyList());
	}

	@Test
	void reportsNoSharedConceptWhenTheTwoCohortsShareNothing() {
		givenConcept(targetCohortId, "State 관리", "79.2", 24);
		givenConcept(baselineCohortId, "트랜잭션", "76.8", 24);

		CohortComparisonResponse response = compare();

		assertThat(response.emptyStateCode()).isEqualTo(ComparisonEmptyState.NO_SHARED_CONCEPT);
		assertThat(response.concepts()).isEmpty();
	}

	@Test
	void alwaysSendsTheAbsoluteScaleSoTheClientNeverRecomputesIt() {
		givenConcept(targetCohortId, "Graph 구성", "60", 24);
		givenConcept(baselineCohortId, "Graph 구성", "69.6", 24);

		CohortComparisonResponse response = compare();

		assertThat(response.levelScale().min()).isZero();
		assertThat(response.levelScale().max()).isEqualTo(4);
		assertThat(response.levelScale().bandThresholds())
				.containsExactly(
						new BigDecimal("0.5"), new BigDecimal("1.5"),
						new BigDecimal("2.5"), new BigDecimal("3.5"));
		assertThat(response.changeThreshold().worsened()).isEqualByComparingTo(new BigDecimal("-0.3"));
		assertThat(response.changeThreshold().improved()).isEqualByComparingTo(new BigDecimal("0.3"));
	}

	@Test
	void rejectsBaselineCohortOutsideTheOrganization() {
		givenCandidates(new CohortComparisonQueryRepository.BaselineCandidateRow(baselineCohortId, "6기", true));

		assertThatThrownBy(() -> service.findCohortComparison(
				targetCohortId, UUID.randomUUID(), ComparisonSort.WORSENED, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		verify(queryRepository, never()).aggregateConceptLevels(any(), anyList());
	}

	@Test
	void rejectsCohortOwnedByAnotherOrganization() {
		when(queryRepository.findCohort(targetCohortId)).thenReturn(Optional.of(
				new CohortComparisonQueryRepository.CohortRow(targetCohortId, "7기", UUID.randomUUID())));

		assertThatThrownBy(this::compare)
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(queryRepository, never()).aggregateConceptLevels(any(), anyList());
	}

	@Test
	void rejectsUnknownCohort() {
		when(queryRepository.findCohort(targetCohortId)).thenReturn(Optional.empty());

		assertThatThrownBy(this::compare)
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void rejectsTraineeAskingForTheComparison() {
		givenActor(Role.TRAINEE);

		assertThatThrownBy(this::compare)
				.isInstanceOfSatisfying(ResponseStatusException.class, exception ->
						assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	private CohortComparisonResponse compare() {
		return compare(ComparisonSort.WORSENED);
	}

	private CohortComparisonResponse compare(ComparisonSort sort) {
		return service.findCohortComparison(targetCohortId, baselineCohortId, sort, ACTOR_EMAIL);
	}

	private int bandOf(String average) {
		levels.clear();
		givenConcept(targetCohortId, "Graph 구성", average, 1);
		givenConcept(baselineCohortId, "Graph 구성", "2", 1);
		return compare().concepts().get(0).target().levelBand();
	}

	private ChangeDirection directionOf(String baselineAverage, String targetAverage) {
		levels.clear();
		givenConcept(targetCohortId, "Graph 구성", targetAverage, 1);
		givenConcept(baselineCohortId, "Graph 구성", baselineAverage, 1);
		return compare().concepts().get(0).change().direction();
	}

	private CohortComparisonResponse.ConceptComparison conceptNamed(
			CohortComparisonResponse response, String conceptName) {
		return response.concepts().stream()
				.filter(concept -> concept.conceptName().equals(conceptName))
				.findFirst()
				.orElseThrow(() -> new AssertionError("개념을 찾을 수 없습니다: " + conceptName));
	}

	private void givenConcept(UUID cohortId, String conceptName, String levelSum, long participantCount) {
		levels.add(conceptRow(cohortId, conceptName, levelSum, participantCount, "ACTIVE", "SINGLE_SOURCE", 1));
	}

	private CohortComparisonQueryRepository.ConceptLevelRow conceptRow(
			UUID cohortId,
			String conceptName,
			String levelSum,
			long participantCount,
			String teachesStatus,
			String aggregationStatus,
			Integer versionNo
	) {
		return new CohortComparisonQueryRepository.ConceptLevelRow(
				cohortId,
				teachesIdOf(conceptName),
				conceptName,
				teachesStatus,
				null,
				new BigDecimal(levelSum),
				participantCount,
				0,
				aggregationStatus,
				UUID.randomUUID(),
				versionNo,
				"AI_LLMOps",
				null,
				null,
				null,
				null
		);
	}

	private CohortComparisonQueryRepository.ConceptLevelRow sourcedRow(
			UUID cohortId, String conceptName, Integer sectionSequenceNo, Integer pageStart) {
		return new CohortComparisonQueryRepository.ConceptLevelRow(
				cohortId,
				teachesIdOf(conceptName),
				conceptName,
				"ACTIVE",
				null,
				new BigDecimal("60"),
				24,
				0,
				"SINGLE_SOURCE",
				UUID.randomUUID(),
				1,
				"AI_LLMOps",
				sectionSequenceNo,
				sectionSequenceNo + "장",
				pageStart,
				pageStart + 3
		);
	}

	/** 같은 개념은 두 기수에서 같은 teaches_id를 가져야 매칭되므로 이름에서 결정적으로 만든다. */
	private UUID teachesIdOf(String conceptName) {
		return UUID.nameUUIDFromBytes(conceptName.getBytes());
	}

	private void givenOperator() {
		givenActor(Role.OPERATOR);
		when(queryRepository.findCohort(targetCohortId)).thenReturn(Optional.of(
				new CohortComparisonQueryRepository.CohortRow(targetCohortId, "7기", organizationId)));
		givenCandidates(new CohortComparisonQueryRepository.BaselineCandidateRow(baselineCohortId, "6기", true));
		when(queryRepository.hasPublishedDiagnosis(eq(targetCohortId), any())).thenReturn(true);
	}

	private void givenCandidates(CohortComparisonQueryRepository.BaselineCandidateRow... candidates) {
		when(queryRepository.findBaselineCandidates(organizationId, targetCohortId))
				.thenReturn(List.of(candidates));
	}

	private void givenActor(Role role) {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(),
				organizationId,
				ACTOR_EMAIL,
				"Actor",
				"hash",
				"ACTIVE",
				true,
				null,
				role,
				"ACTIVE"
		)));
	}
}
