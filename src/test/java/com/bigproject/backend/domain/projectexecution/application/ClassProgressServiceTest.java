package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.analytics.application.AnalyticsActorGuard;
import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.projectexecution.domain.ClassProgressQueryRepository;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClassProgressServiceTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private static final String MANAGER_EMAIL = "manager@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final ManagerViewScopeGuard managerViewScopeGuard = mock(ManagerViewScopeGuard.class);
	private final ClassProgressQueryRepository classProgressQueryRepository =
			mock(ClassProgressQueryRepository.class);
	private final ClassProgressService service = new ClassProgressService(
			new AnalyticsActorGuard(authUserRepository), managerViewScopeGuard, classProgressQueryRepository);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();
	private final UUID managerUserId = UUID.randomUUID();

	@BeforeEach
	void givenOperatorAndRound() {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(), organizationId, ACTOR_EMAIL, "Actor", "hash",
				"ACTIVE", true, null, Role.OPERATOR, "ACTIVE")));
		when(authUserRepository.findByNormalizedEmail(MANAGER_EMAIL)).thenReturn(Optional.of(new AuthUser(
				managerUserId, organizationId, MANAGER_EMAIL, "Manager", "hash",
				"ACTIVE", true, null, Role.MANAGER, "ACTIVE")));
		when(classProgressQueryRepository.findRound(projectId, 1)).thenReturn(Optional.of(
				new ClassProgressQueryRepository.RoundScope(
						roundId, projectId, "미프 3차", 1, "RAG 파이프라인", organizationId, cohortId,
						java.time.Instant.parse("2026-08-06T09:00:00Z"), "ROUND_BATCH", false, 6)));
		when(classProgressQueryRepository.findRoundSummary(roundId, organizationId, null)).thenReturn(
				new ClassProgressQueryRepository.RoundSummaryRow(250, 231, 231, 223, 223, 198));
	}

	@Test
	void keepsTheFunnelDenominatorsInOneResponse() {
		// 제출 24, 분석 성공 23, 응시 20 — 응시율 분모가 제출이 아니라 분석 성공이다.
		givenClass(25, 24, 23, 1, 0, 0, 20);

		ClassProgressResponse.ClassProgress row =
				service.findClassProgress(projectId, 1, ACTOR_EMAIL).classes().get(0);

		assertThat(row.targetTraineeCount()).isEqualTo(25);
		assertThat(row.submittedCount()).isEqualTo(24);
		assertThat(row.analysisSucceededCount()).isEqualTo(23);
		assertThat(row.assessedCount()).isEqualTo(20);
	}

	@Test
	void keepsPartialAndInProgressSoTheSubmittedTotalStillAddsUp() {
		// PARTIAL 은 분석 완료로 세지 않기로 했다. 그래도 어디에도 안 잡히고 사라지면 안 된다.
		givenClass(25, 24, 20, 1, 2, 1, 18);

		ClassProgressResponse.ClassProgress row =
				service.findClassProgress(projectId, 1, ACTOR_EMAIL).classes().get(0);

		assertThat(row.analysisPartialCount()).isEqualTo(2);
		assertThat(row.analysisInProgressCount()).isEqualTo(1);
		assertThat(row.analysisSucceededCount()
				+ row.analysisFailedCount()
				+ row.analysisPartialCount()
				+ row.analysisInProgressCount())
				.isEqualTo(row.submittedCount());
	}

	@Test
	void reportsAnEmptyManagerListRatherThanOmittingTheClass() {
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId, null)).thenReturn(List.of(
				new ClassProgressQueryRepository.ClassProgressRow(
						classId, "F반", 25, 22, 22, 0, 0, 0, 18, 3, 1, 0, List.of())
		));

		ClassProgressResponse.ClassProgress row =
				service.findClassProgress(projectId, 1, ACTOR_EMAIL).classes().get(0);

		// 담당 없음은 대시보드의 미배정 경보와 같은 조건이라 빈 배열로 드러나야 한다.
		assertThat(row.managerNames()).isEmpty();
		assertThat(row.className()).isEqualTo("F반");
	}

	@Test
	void carriesConceptMatchCountsForTheSameRound() {
		when(classProgressQueryRepository.findConceptMatches(roundId, organizationId, null)).thenReturn(List.of(
				new ClassProgressQueryRepository.ConceptMatchRow(
						UUID.randomUUID(), "Snapshot 개념과 구성 요소", 227, 84, 31)
		));

		ClassProgressResponse.ConceptMatch match =
				service.findClassProgress(projectId, 1, ACTOR_EMAIL).conceptMatches().get(0);

		assertThat(match.analysedTraineeCount()).isEqualTo(227);
		assertThat(match.matchedTraineeCount()).isEqualTo(84);
		assertThat(match.unmatchedTeamCount()).isEqualTo(31);
	}

	@Test
	void carriesRoundScheduleAndReportPublishState() {
		givenClass(25, 24, 23, 1, 0, 0, 20);

		ClassProgressResponse response = service.findClassProgress(projectId, 1, ACTOR_EMAIL);

		assertThat(response.totalRoundCount()).isEqualTo(6);
		assertThat(response.submissionDueAt()).isEqualTo(java.time.Instant.parse("2026-08-06T09:00:00Z"));
		assertThat(response.reportPublishMode()).isEqualTo("ROUND_BATCH");
		assertThat(response.reportPublished()).isFalse();
	}

	@Test
	void carriesRoundWideSummaryIndependentlyOfClasses() {
		givenClass(25, 24, 23, 1, 0, 0, 20);

		ClassProgressResponse.Summary summary =
				service.findClassProgress(projectId, 1, ACTOR_EMAIL).summary();

		assertThat(summary.targetTraineeCount()).isEqualTo(250);
		assertThat(summary.submittedCount()).isEqualTo(231);
		assertThat(summary.analysisTargetCount()).isEqualTo(summary.submittedCount());
		assertThat(summary.analysisSucceededCount()).isEqualTo(223);
		assertThat(summary.assessmentTargetCount()).isEqualTo(summary.analysisSucceededCount());
		assertThat(summary.assessedCount()).isEqualTo(198);
	}

	@Test
	void attachesFailedTeamsToTheirOwnClassAndLeavesOthersEmpty() {
		UUID otherClassId = UUID.randomUUID();
		UUID representativeUserId = UUID.randomUUID();
		UUID failedTeamId = UUID.randomUUID();
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId, null)).thenReturn(List.of(
				new ClassProgressQueryRepository.ClassProgressRow(
						classId, "B반", 25, 24, 23, 1, 0, 0, 20, 2, 1, 0, List.of("이도윤")),
				new ClassProgressQueryRepository.ClassProgressRow(
						otherClassId, "C반", 25, 25, 25, 0, 0, 0, 25, 0, 0, 0, List.of("박서준"))
		));
		when(classProgressQueryRepository.findFailedTeams(roundId, organizationId, null)).thenReturn(List.of(
				new ClassProgressQueryRepository.FailedTeamRow(
						classId, failedTeamId, "3팀", representativeUserId, "김민준", "REPOSITORY_ACCESS_DENIED")
		));

		List<ClassProgressResponse.ClassProgress> rows =
				service.findClassProgress(projectId, 1, ACTOR_EMAIL).classes();
		ClassProgressResponse.ClassProgress failedClassRow =
				rows.stream().filter(row -> row.classId().equals(classId)).findFirst().orElseThrow();
		ClassProgressResponse.ClassProgress cleanClassRow =
				rows.stream().filter(row -> row.classId().equals(otherClassId)).findFirst().orElseThrow();

		assertThat(failedClassRow.failedTeams()).hasSize(1);
		ClassProgressResponse.FailedTeam failedTeam = failedClassRow.failedTeams().get(0);
		assertThat(failedTeam.teamId()).isEqualTo(failedTeamId);
		assertThat(failedTeam.teamName()).isEqualTo("3팀");
		assertThat(failedTeam.representativeUserId()).isEqualTo(representativeUserId);
		assertThat(failedTeam.representativeName()).isEqualTo("김민준");
		assertThat(failedTeam.failureReason()).isEqualTo("REPOSITORY_ACCESS_DENIED");
		assertThat(cleanClassRow.failedTeams()).isEmpty();
	}

	@Test
	void rejectsProjectOwnedByAnotherOrganization() {
		when(classProgressQueryRepository.findRound(projectId, 1)).thenReturn(Optional.of(
				new ClassProgressQueryRepository.RoundScope(
						roundId, projectId, "미프 3차", 1, "RAG 파이프라인", UUID.randomUUID(), cohortId,
						java.time.Instant.parse("2026-08-06T09:00:00Z"), "ROUND_BATCH", false, 6)));

		assertThatThrownBy(() -> service.findClassProgress(projectId, 1, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.PROJECT_CROSS_ORGANIZATION));
		verify(classProgressQueryRepository, never()).findClassProgress(any(), any(), any());
	}

	/** 회차는 있는데 <b>그 번호</b>가 없다. 화면은 회차 드롭다운을 되돌리면 된다. */
	@Test
	void rejectsUnknownRound() {
		when(classProgressQueryRepository.findRound(projectId, 9)).thenReturn(Optional.empty());
		when(classProgressQueryRepository.hasAnyRound(projectId)).thenReturn(true);

		assertThatThrownBy(() -> service.findClassProgress(projectId, 9, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.PROJECT_ROUND_NOT_FOUND));
	}

	/**
	 * 22차 R6 — 회차가 <b>하나도 없는</b> 프로젝트. 같은 404지만 화면이 할 일이 정반대라
	 * 코드를 나눈다 — 드롭다운을 되돌릴 것이 아니라 「회차 준비 중」으로 그리고 기다려야 한다.
	 *
	 * <p>22차 이전에 만들어진 프로젝트에서만 난다. 그때는 프로젝트를 만들어도 회차를 만들지 않았다.
	 */
	@Test
	void tellsTheRoundWasNeverCreatedApartFromAskingForAMissingNumber() {
		when(classProgressQueryRepository.findRound(projectId, 1)).thenReturn(Optional.empty());
		when(classProgressQueryRepository.hasAnyRound(projectId)).thenReturn(false);

		assertThatThrownBy(() -> service.findClassProgress(projectId, 1, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.PROJECT_ROUND_NOT_CREATED));
	}

	/**
	 * 30차 R3 — <b>매니저는 네 질의 모두 담당 반으로 좁혀 받는다.</b>
	 *
	 * <p>{@code summary}까지 좁히는 것이 핵심이다. 좁히지 않으면 같은 응답 안에서 합계가 기수
	 * 전체(208명)를, 반 행이 담당 반(26명)을 말하게 된다 — 탭 사이의 불일치를 응답 안으로
	 * 옮기는 것일 뿐이다.
	 */
	@Test
	void narrowsEveryQueryToTheClassesTheManagerOwns() {
		when(managerViewScopeGuard.managesCohort(managerUserId, organizationId, cohortId)).thenReturn(true);
		when(classProgressQueryRepository.findRoundSummary(roundId, organizationId, managerUserId))
				.thenReturn(new ClassProgressQueryRepository.RoundSummaryRow(26, 26, 26, 25, 25, 24));
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId, managerUserId))
				.thenReturn(List.of(new ClassProgressQueryRepository.ClassProgressRow(
						classId, "C반", 26, 26, 25, 1, 0, 0, 24, 1, 0, 0, List.of("이도윤"))));

		ClassProgressResponse response = service.findClassProgress(projectId, 1, MANAGER_EMAIL);

		assertThat(response.summary().targetTraineeCount()).isEqualTo(26);
		assertThat(response.classes()).singleElement()
				.satisfies(row -> assertThat(row.className()).isEqualTo("C반"));
		// 기수 전체를 세는 호출이 하나라도 남으면 탭마다 숫자가 달라진다.
		verify(classProgressQueryRepository, never()).findRoundSummary(any(), any(), isNull());
		verify(classProgressQueryRepository, never()).findClassProgress(any(), any(), isNull());
		verify(classProgressQueryRepository, never()).findConceptMatches(any(), any(), isNull());
		verify(classProgressQueryRepository, never()).findFailedTeams(any(), any(), isNull());
	}

	/** 오퍼레이터는 좁히지 않는다. 담당 반이라는 개념이 없어 기수 전체가 그의 모집단이다. */
	@Test
	void leavesTheOperatorSeeingTheWholeCohort() {
		givenClass(25, 24, 23, 1, 0, 0, 20);

		service.findClassProgress(projectId, 1, ACTOR_EMAIL);

		verify(classProgressQueryRepository).findRoundSummary(roundId, organizationId, null);
		verify(classProgressQueryRepository).findClassProgress(roundId, organizationId, null);
		verifyNoInteractions(managerViewScopeGuard);
	}

	/**
	 * 담당 반이 하나도 없는 기수를 매니저가 열면 <b>404로 끊는다.</b> 좁히기만 하면 그 화면은
	 * 「반 0개 · 인원 0명」이 되어, 권한이 없다는 사실이 「아직 데이터가 없다」로 보인다 —
	 * 화면이 기다리라고 안내하게 되고 기다려도 달라지지 않는다.
	 */
	@Test
	void refusesACohortTheManagerDoesNotOwnRatherThanReturningZeros() {
		when(managerViewScopeGuard.managesCohort(managerUserId, organizationId, cohortId)).thenReturn(false);

		assertThatThrownBy(() -> service.findClassProgress(projectId, 1, MANAGER_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.errorCode())
						.isEqualTo(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND));
		verify(classProgressQueryRepository, never()).findClassProgress(any(), any(), any());
	}

	private void givenClass(
			long target, long submitted, long succeeded, long failed,
			long partial, long inProgress, long assessed
	) {
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId, null)).thenReturn(List.of(
				new ClassProgressQueryRepository.ClassProgressRow(
						classId, "B반", target, submitted, succeeded, failed, partial, inProgress,
						assessed, 2, 1, 0, List.of("이도윤"))
		));
	}
}
