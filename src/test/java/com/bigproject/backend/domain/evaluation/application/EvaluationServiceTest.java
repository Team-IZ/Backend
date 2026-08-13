package com.bigproject.backend.domain.evaluation.application;

import com.bigproject.backend.domain.evaluation.domain.EvaluationErrorCode;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.ConceptResultRow;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.RoundScope;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.StageRow;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.TraineeRow;
import com.bigproject.backend.domain.evaluation.presentation.dto.ProjectEvaluationSummaryResponse;
import com.bigproject.backend.domain.evaluation.presentation.dto.TraineeEvaluationDetailResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 결과 탭이 화면에 넘기지 않기로 한 판정들을 고정한다.
 *
 * <p>특히 "막혔다"와 "코드에 없었다"의 구분이다. 둘을 섞으면 매니저가 못한 사람과 묻지 못한 사람에게
 * 같은 말을 하게 된다.
 */
class EvaluationServiceTest {

	private static final String EMAIL = "manager@example.com";
	private static final UUID PROJECT_ID = UUID.randomUUID();
	private static final UUID ROUND_ID = UUID.randomUUID();
	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID COHORT_ID = UUID.randomUUID();
	private static final UUID CLASS_ID = UUID.randomUUID();
	private static final UUID MANAGER_ID = UUID.randomUUID();
	private static final UUID USER_A = UUID.randomUUID();
	private static final UUID USER_B = UUID.randomUUID();
	private static final UUID CONCEPT_ID = UUID.randomUUID();
	private static final UUID PROBLEM_ID = UUID.randomUUID();

	private EvaluationQueryRepository repository;
	private ManagerViewScopeGuard scopeGuard;
	private EvaluationService service;

	@BeforeEach
	void setUp() {
		repository = mock(EvaluationQueryRepository.class);
		scopeGuard = mock(ManagerViewScopeGuard.class);
		service = new EvaluationService(repository, scopeGuard);

		when(scopeGuard.requireCohort(any(), any()))
				.thenReturn(new ManagerViewScopeGuard.ManagerActor(MANAGER_ID, ORG_ID));
		when(repository.findRound(PROJECT_ID, 1)).thenReturn(Optional.of(round(false)));
		when(repository.isClassManagedBy(any(), any(), any())).thenReturn(true);
		when(repository.findTrainees(any(), any(), any(), any())).thenReturn(List.of());
		when(repository.findConceptResults(any(), any(), any(), any(), any())).thenReturn(List.of());
		when(repository.findStages(any(), any())).thenReturn(List.of());
	}

	@Test
	void failsWhenTheRoundDoesNotExist() {
		when(repository.findRound(PROJECT_ID, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findSummary(EMAIL, PROJECT_ID, 9, null))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(EvaluationErrorCode.PROJECT_ROUND_NOT_FOUND);
	}

	@Test
	void countsAConceptAsStuckOnlyWhenItWasActuallyAsked() {
		// 코드에 없어 문제를 못 만든 개념은 '막힘'이 아니다 -- 못한 것이 아니라 묻지 못한 것이다.
		when(repository.findTrainees(any(), any(), any(), any()))
				.thenReturn(List.of(trainee(USER_A, "김민준", "COMPLETED", "COMPLETED", "NOT_REQUIRED")));
		when(repository.findConceptResults(any(), any(), any(), any(), any())).thenReturn(List.of(
				concept(USER_A, CONCEPT_ID, "HITL 트리거", 1, true, 1),
				concept(USER_A, UUID.randomUUID(), "State 관리", 2, false, 0)));

		var response = service.findSummary(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.trainees()).hasSize(1);
		assertThat(response.trainees().get(0).stuckConceptCount()).isEqualTo(1);
		assertThat(response.conceptAggregates())
				.extracting(ProjectEvaluationSummaryResponse.ConceptAggregate::concept,
						ProjectEvaluationSummaryResponse.ConceptAggregate::stuckCount,
						ProjectEvaluationSummaryResponse.ConceptAggregate::notInCodeCount)
				.containsExactly(
						tuple("HITL 트리거", 1L, 0L),
						tuple("State 관리", 0L, 1L));
	}

	@Test
	void treatsTwoLevelsAsThePassLine() {
		when(repository.findTrainees(any(), any(), any(), any())).thenReturn(List.of(
				trainee(USER_A, "가", "COMPLETED", "COMPLETED", "NOT_REQUIRED"),
				trainee(USER_B, "나", "COMPLETED", "COMPLETED", "NOT_REQUIRED")));
		when(repository.findConceptResults(any(), any(), any(), any(), any())).thenReturn(List.of(
				concept(USER_A, CONCEPT_ID, "HITL 트리거", 1, true, 1),
				concept(USER_B, CONCEPT_ID, "HITL 트리거", 1, true, 2)));

		var response = service.findSummary(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.summary().failedCount()).isEqualTo(1);
		assertThat(response.trainees()).extracting(
						ProjectEvaluationSummaryResponse.Trainee::name,
						ProjectEvaluationSummaryResponse.Trainee::stuckConceptCount)
				.containsExactly(
						tuple("가", 1L),
						tuple("나", 0L));
	}

	@Test
	void warnsOnlyWhenMoreThanHalfOfTheAssessedAreStuck() {
		// 절반 '이상'이 아니라 초과다 -- 둘이 갈리는 경계(2명 중 1명)를 고정한다.
		when(repository.findTrainees(any(), any(), any(), any())).thenReturn(List.of(
				trainee(USER_A, "가", "COMPLETED", "COMPLETED", "NOT_REQUIRED"),
				trainee(USER_B, "나", "COMPLETED", "COMPLETED", "NOT_REQUIRED")));
		when(repository.findConceptResults(any(), any(), any(), any(), any())).thenReturn(List.of(
				concept(USER_A, CONCEPT_ID, "HITL 트리거", 1, true, 0),
				concept(USER_B, CONCEPT_ID, "HITL 트리거", 1, true, 4)));

		assertThat(service.findSummary(EMAIL, PROJECT_ID, 1, null).classWarnings()).isEmpty();
	}

	@Test
	void readsAnInvalidAttemptBeforeAnythingElse() {
		// 무효 확정은 응시를 마쳤더라도 결과로 읽으면 안 된다.
		when(repository.findTrainees(any(), any(), any(), any())).thenReturn(List.of(
				trainee(USER_A, "가", "COMPLETED", "COMPLETED", "CONFIRMED_INVALID"),
				trainee(USER_B, "나", "NOT_STARTED", "NOT_ATTENDED", "NOT_REQUIRED")));

		var response = service.findSummary(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.trainees()).extracting(ProjectEvaluationSummaryResponse.Trainee::resultStatus)
				.containsExactly("INVALID", "NOT_ATTENDED");
		assertThat(response.summary().attendedCount()).isZero();
		assertThat(response.summary().invalidCount()).isEqualTo(1);
		assertThat(response.summary().notAttendedCount()).isEqualTo(1);
	}

	@Test
	void rejectsATraineeOutsideTheManagerScope() {
		when(repository.findTrainees(any(), any(), any(), any()))
				.thenReturn(List.of(trainee(USER_A, "가", "COMPLETED", "COMPLETED", "NOT_REQUIRED")));

		assertThatThrownBy(() -> service.findTraineeDetail(EMAIL, PROJECT_ID, 1, USER_B))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(EvaluationErrorCode.EVALUATION_TRAINEE_NOT_FOUND);
	}

	@Test
	void ordersStepsAlongTheLadderAndKeepsOnlyAskedOnes() {
		when(repository.findTrainees(any(), any(), any(), any()))
				.thenReturn(List.of(trainee(USER_A, "가", "COMPLETED", "COMPLETED", "NOT_REQUIRED")));
		when(repository.findConceptResults(any(), any(), any(), any(), any()))
				.thenReturn(List.of(concept(USER_A, CONCEPT_ID, "HITL 트리거", 1, true, 2)));
		when(repository.findStages(ROUND_ID, USER_A)).thenReturn(List.of(
				new StageRow(PROBLEM_ID, "L2", false, 2, 2, "고려사항을 나열했으나 선택과 연결되지 않았다"),
				new StageRow(PROBLEM_ID, "L1", true, 0, 4, "요소들이 어떻게 이어지는지 설명했다")));

		var response = service.findTraineeDetail(EMAIL, PROJECT_ID, 1, USER_A);

		assertThat(response.concepts()).hasSize(1);
		assertThat(response.concepts().get(0).steps())
				.extracting(TraineeEvaluationDetailResponse.Step::axisCode,
						TraineeEvaluationDetailResponse.Step::passed,
						TraineeEvaluationDetailResponse.Step::helpCount)
				.containsExactly(
						tuple("L1", true, 0),
						tuple("L2", false, 2));
		assertThat(response.concepts().get(0).retryTarget()).isFalse();
	}

	private RoundScope round(boolean published) {
		return new RoundScope(ROUND_ID, PROJECT_ID, ORG_ID, COHORT_ID, "미프 3차", 1, "3차", published, null);
	}

	private TraineeRow trainee(
			UUID userId, String name, String completionStatus, String terminalReason, String validity) {
		return new TraineeRow(userId, name, CLASS_ID, "A반", completionStatus, terminalReason, validity);
	}

	private ConceptResultRow concept(
			UUID userId, UUID conceptId, String name, int order, boolean generated, int reachLevel) {
		return new ConceptResultRow(userId, conceptId, name, order, PROBLEM_ID, generated, reachLevel);
	}
}
