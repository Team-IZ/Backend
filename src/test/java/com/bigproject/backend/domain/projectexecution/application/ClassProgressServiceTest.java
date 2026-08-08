package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.analytics.application.AnalyticsActorGuard;
import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.projectexecution.domain.ClassProgressQueryRepository;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
import com.bigproject.backend.global.exception.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClassProgressServiceTest {
	private static final String ACTOR_EMAIL = "lead@example.com";

	private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
	private final ClassProgressQueryRepository classProgressQueryRepository =
			mock(ClassProgressQueryRepository.class);
	private final ClassProgressService service = new ClassProgressService(
			new AnalyticsActorGuard(authUserRepository), classProgressQueryRepository);

	private final UUID organizationId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID roundId = UUID.randomUUID();
	private final UUID classId = UUID.randomUUID();

	@BeforeEach
	void givenOperatorAndRound() {
		when(authUserRepository.findByNormalizedEmail(ACTOR_EMAIL)).thenReturn(Optional.of(new AuthUser(
				UUID.randomUUID(), organizationId, ACTOR_EMAIL, "Actor", "hash",
				"ACTIVE", true, null, Role.OPERATOR, "ACTIVE")));
		when(classProgressQueryRepository.findRound(projectId, 1)).thenReturn(Optional.of(
				new ClassProgressQueryRepository.RoundScope(
						roundId, projectId, "미프 3차", 1, "RAG 파이프라인", organizationId,
						java.time.Instant.parse("2026-08-06T09:00:00Z"), "ROUND_BATCH", false, 6)));
		when(classProgressQueryRepository.findRoundSummary(roundId, organizationId)).thenReturn(
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
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId)).thenReturn(List.of(
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
		when(classProgressQueryRepository.findConceptMatches(roundId, organizationId)).thenReturn(List.of(
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
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId)).thenReturn(List.of(
				new ClassProgressQueryRepository.ClassProgressRow(
						classId, "B반", 25, 24, 23, 1, 0, 0, 20, 2, 1, 0, List.of("이도윤")),
				new ClassProgressQueryRepository.ClassProgressRow(
						otherClassId, "C반", 25, 25, 25, 0, 0, 0, 25, 0, 0, 0, List.of("박서준"))
		));
		when(classProgressQueryRepository.findFailedTeams(roundId, organizationId)).thenReturn(List.of(
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
						roundId, projectId, "미프 3차", 1, "RAG 파이프라인", UUID.randomUUID(),
						java.time.Instant.parse("2026-08-06T09:00:00Z"), "ROUND_BATCH", false, 6)));

		assertThatThrownBy(() -> service.findClassProgress(projectId, 1, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.PROJECT_CROSS_ORGANIZATION));
		verify(classProgressQueryRepository, never()).findClassProgress(any(), any());
	}

	@Test
	void rejectsUnknownRound() {
		when(classProgressQueryRepository.findRound(projectId, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findClassProgress(projectId, 9, ACTOR_EMAIL))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AnalyticsErrorCode.PROJECT_ROUND_NOT_FOUND));
	}

	private void givenClass(
			long target, long submitted, long succeeded, long failed,
			long partial, long inProgress, long assessed
	) {
		when(classProgressQueryRepository.findClassProgress(roundId, organizationId)).thenReturn(List.of(
				new ClassProgressQueryRepository.ClassProgressRow(
						classId, "B반", target, submitted, succeeded, failed, partial, inProgress,
						assessed, 2, 1, 0, List.of("이도윤"))
		));
	}
}
