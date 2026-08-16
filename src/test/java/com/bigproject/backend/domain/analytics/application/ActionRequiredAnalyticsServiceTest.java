package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.analytics.domain.OperationalActionQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.presentation.dto.ActionRequiredResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionRequiredAnalyticsServiceTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final RiskTraineeQueryRepository riskTraineeQueryRepository = mock(RiskTraineeQueryRepository.class);
	private final OperationalActionQueryRepository operationalActionQueryRepository =
			mock(OperationalActionQueryRepository.class);
	private final ClassroomRepository classroomRepository = mock(ClassroomRepository.class);
	private final ActionRequiredAnalyticsService service = new ActionRequiredAnalyticsService(
			new AnalyticsActorGuard(authUserRepository),
			riskTraineeQueryRepository,
			operationalActionQueryRepository,
			classroomRepository
	);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();
	private final UUID teachesId = UUID.randomUUID();
	private final OperationalActionQueryRepository.RoundRef round =
			new OperationalActionQueryRepository.RoundRef(
					UUID.randomUUID(), 3, "RAG 파이프라인", UUID.randomUUID(), "미프 3차");

	@BeforeEach
	void givenOperator() {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(), organizationId, ACTOR_EMAIL, "Actor", "hash",
				"ACTIVE", true, null, Role.OPERATOR, "ACTIVE")));
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, organizationId)));
	}

	@Test
	void countsOnlyTheAlertsThatActuallyFired() {
		when(operationalActionQueryRepository.findUnassignedClasses(cohortId, organizationId))
				.thenReturn(List.of(new OperationalActionQueryRepository.UnassignedClassRow(classId, "F반", 25)));
		when(operationalActionQueryRepository.findConceptGaps(cohortId, organizationId))
				.thenReturn(List.of());
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId))
				.thenReturn(List.of());
		when(operationalActionQueryRepository.findInterviewBacklogs(cohortId, organizationId))
				.thenReturn(List.of());

		ActionRequiredResponse response = service.findActionsRequired(cohortId, ACTOR_EMAIL);

		assertThat(response.actionCount()).isEqualTo(1);
		assertThat(response.managerUnassigned()).isNotNull();
		assertThat(response.conceptGap()).isNull();
		assertThat(response.groupGap()).isNull();
		assertThat(response.interviewBacklog()).isNull();
	}

	@Test
	void sumsTraineesAcrossEveryClassWithoutAManager() {
		when(operationalActionQueryRepository.findUnassignedClasses(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.UnassignedClassRow(classId, "F반", 25),
				new OperationalActionQueryRepository.UnassignedClassRow(UUID.randomUUID(), "I반", 24)
		));

		ActionRequiredResponse.ManagerUnassignedAlert alert =
				service.findActionsRequired(cohortId, ACTOR_EMAIL).managerUnassigned();

		assertThat(alert.classes()).hasSize(2);
		assertThat(alert.affectedTraineeCount()).isEqualTo(49);
	}

	@Test
	void picksTheWorstConceptGapWhichTheQueryAlreadyOrdersFirst() {
		when(operationalActionQueryRepository.findConceptGaps(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.ConceptGapRow(round, teachesId, "State 관리", 6, 8),
				new OperationalActionQueryRepository.ConceptGapRow(round, UUID.randomUUID(), "캐시 전략", 2, 8)
		));

		ActionRequiredResponse.ConceptGapAlert alert =
				service.findActionsRequired(cohortId, ACTOR_EMAIL).conceptGap();

		assertThat(alert.conceptName()).isEqualTo("State 관리");
		assertThat(alert.gapTeamCount()).isEqualTo(6);
		assertThat(alert.participatingTeamCount()).isEqualTo(8);
		assertThat(alert.round().projectName()).isEqualTo("미프 3차");
	}

	@Test
	void raisesGroupGapOnlyWhenMoreThanHalfTheClassIsLowLevel() {
		// 14/25 = 56% 는 절반을 넘어 반 문제, 12/25 = 48% 는 아니다.
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.GroupGapRow(
						round, classId, "C반", teachesId, "Graph 구성", 12, 25)
		));

		assertThat(service.findActionsRequired(cohortId, ACTOR_EMAIL).groupGap()).isNull();

		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.GroupGapRow(
						round, classId, "C반", teachesId, "Graph 구성", 14, 25)
		));

		ActionRequiredResponse.GroupGapAlert alert =
				service.findActionsRequired(cohortId, ACTOR_EMAIL).groupGap();
		assertThat(alert.className()).isEqualTo("C반");
		assertThat(alert.lowLevelCount()).isEqualTo(14);
		assertThat(alert.classMemberCount()).isEqualTo(25);
	}

	@Test
	void doesNotRaiseGroupGapOnExactlyHalf() {
		// 경계는 초과여야 미달이다. 정확히 절반이면 반 문제로 올리지 않는다.
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.GroupGapRow(
						round, classId, "C반", teachesId, "Graph 구성", 12, 24)
		));

		assertThat(service.findActionsRequired(cohortId, ACTOR_EMAIL).groupGap()).isNull();
	}

	@Test
	void skipsClassesBelowThresholdToReachTheFirstRealGroupGap() {
		when(operationalActionQueryRepository.findGroupGaps(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.GroupGapRow(
						round, classId, "A반", teachesId, "REST 설계", 10, 25),
				new OperationalActionQueryRepository.GroupGapRow(
						round, UUID.randomUUID(), "C반", teachesId, "Graph 구성", 14, 25)
		));

		assertThat(service.findActionsRequired(cohortId, ACTOR_EMAIL).groupGap().className()).isEqualTo("C반");
	}

	@Test
	void surfacesInterviewsThatCouldNotBeMeasuredAlongsideTheDelay() {
		when(operationalActionQueryRepository.findInterviewBacklogs(cohortId, organizationId)).thenReturn(List.of(
				new OperationalActionQueryRepository.InterviewBacklogRow(
						round, classId, "D반", 11, 7, 2, 1)
		));

		ActionRequiredResponse.InterviewBacklogAlert alert =
				service.findActionsRequired(cohortId, ACTOR_EMAIL).interviewBacklog();

		assertThat(alert.maxDelayDays()).isEqualTo(11);
		assertThat(alert.pendingInterviewCount()).isEqualTo(7);
		// 예정일이 없어 지연일을 잴 수 없는 건은 별도로 드러내야 놓치지 않는다.
		assertThat(alert.notCreatedCount()).isEqualTo(2);
		assertThat(alert.unplannedCount()).isEqualTo(1);
	}

	@Test
	void returnsNoAlertsWhenNothingNeedsAttention() {
		ActionRequiredResponse response = service.findActionsRequired(cohortId, ACTOR_EMAIL);

		assertThat(response.actionCount()).isZero();
		assertThat(response.managerUnassigned()).isNull();
	}

	@Test
	void rejectsCohortOwnedByAnotherOrganization() {
		when(riskTraineeQueryRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new RiskTraineeQueryRepository.CohortScope(cohortId, UUID.randomUUID())));

		assertThatThrownBy(() -> service.findActionsRequired(cohortId, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.ANALYTICS_COHORT_CROSS_ORGANIZATION));
	}

	@Test
	void rejectsUnknownCohort() {
		when(riskTraineeQueryRepository.findCohortScope(cohortId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findActionsRequired(cohortId, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
	}

	// =========================================================================
	// getActionRequiredProjects (MG-07, classId 기준) — 신규 테스트
	// =========================================================================

	@Test
	void getActionRequiredProjects_returnsCohortIdFromTheResolvedClassroom() {
		Classroom classroom = mock(Classroom.class);
		when(classroom.getCohortId()).thenReturn(cohortId);
		when(classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, organizationId))
				.thenReturn(Optional.of(classroom));

		ActionRequiredResponse response = service.getActionRequiredProjects(classId, ACTOR_EMAIL);

		assertThat(response.cohortId()).isEqualTo(cohortId);
		assertThat(response.actionCount()).isZero();
	}

	@Test
	void getActionRequiredProjects_rejectsClassroomFromAnotherOrganization() {
		// 다른 기관 소속이면 classId+orgId 조합 쿼리가 빈 값을 돌려준다.
		when(classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, organizationId))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getActionRequiredProjects(classId, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND));
	}

	@Test
	void getActionRequiredProjects_countsAlertsScopedToTheGivenClass() {
		Classroom classroom = mock(Classroom.class);
		when(classroom.getCohortId()).thenReturn(cohortId);
		when(classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, organizationId))
				.thenReturn(Optional.of(classroom));
		when(operationalActionQueryRepository.findUnassignedClassesByClassId(classId))
				.thenReturn(List.of(new OperationalActionQueryRepository.UnassignedClassRow(classId, "F반", 25)));
		when(operationalActionQueryRepository.findConceptGapsByClassId(classId)).thenReturn(List.of());
		when(operationalActionQueryRepository.findGroupGapsByClassId(classId)).thenReturn(List.of());
		when(operationalActionQueryRepository.findInterviewBacklogsByClassId(classId)).thenReturn(List.of());

		ActionRequiredResponse response = service.getActionRequiredProjects(classId, ACTOR_EMAIL);

		assertThat(response.actionCount()).isEqualTo(1);
		assertThat(response.managerUnassigned()).isNotNull();
	}
}