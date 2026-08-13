package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.SubmissionMethodPolicy;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRoundRepository;
import com.bigproject.backend.domain.assessment.domain.TraineeMembership;
import com.bigproject.backend.domain.assessment.presentation.dto.AssessmentRoundsResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssessmentRoundQueryServiceTest {
	private final TraineeHomeRoundRepository repository = mock(TraineeHomeRoundRepository.class);
	private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);
	private final AssessmentRoundQueryService service =
			new AssessmentRoundQueryService(repository, currentUserResolver);

	private final UUID traineeUserId = UUID.randomUUID();
	private static final Instant AS_OF = Instant.parse("2026-08-06T00:14:02Z");

	private void authenticated() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(traineeUserId);
		when(repository.findSubmissionMethodPolicyByUserId(traineeUserId))
				.thenReturn(Optional.of(new SubmissionMethodPolicy(true, true)));
	}

	@Test
	void splitsRoundsIntoThreeSectionsByRoundStatus() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 3, Instant.parse("2026-07-14T09:00:00Z")),
				round("PLANNED", 4, Instant.parse("2026-07-21T09:00:00Z")),
				round("CLOSED", 2, Instant.parse("2026-07-07T09:00:00Z")),
				round("COMPLETED", 1, Instant.parse("2026-06-30T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.current().roundNo()).isEqualTo(3);
		assertThat(response.upcoming()).extracting("roundNo").containsExactly(4);
		// CLOSED와 COMPLETED가 모두 지난 회차이며 최근 회차부터 내려간다.
		assertThat(response.past()).extracting("roundNo").containsExactly(2, 1);
	}

	@Test
	void ordersUpcomingBySubmissionDueAtAndPastByRoundNoDescending() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("PLANNED", 6, Instant.parse("2026-08-11T09:00:00Z")),
				round("PLANNED", 5, Instant.parse("2026-08-04T09:00:00Z")),
				round("CLOSED", 1, Instant.parse("2026-06-30T09:00:00Z")),
				round("CLOSED", 3, Instant.parse("2026-07-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.upcoming()).extracting("roundNo").containsExactly(5, 6);
		assertThat(response.past()).extracting("roundNo").containsExactly(3, 1);
	}

	@Test
	void picksMostUrgentOpenRoundWhenSeveralProjectsRunConcurrently() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 9, Instant.parse("2026-08-20T09:00:00Z")),
				round("OPEN", 3, Instant.parse("2026-08-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 둘 다 마감 전이면 이른 쪽이 "지금 할 일"이다.
		assertThat(response.current().roundNo()).isEqualTo(3);
		// 뽑히지 못한 쪽은 사라지지 않고 예정 구획에 남는다.
		assertThat(response.upcoming()).extracting("roundNo").containsExactly(9);
	}

	@Test
	void neverDropsAnOpenRoundWhoseSubmissionIsStillDue() {
		authenticated();
		// 24차 R4 재현 — 마감이 지난 5차와 마감이 남은 6차가 동시에 OPEN이다.
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 5, Instant.parse("2026-08-01T09:00:00Z")),
				round("OPEN", 6, Instant.parse("2026-08-21T09:00:00Z")),
				round("COMPLETED", 4, Instant.parse("2026-07-17T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 마감이 남은 쪽이 "지금 할 일"이다. 마감이 지난 회차를 여기에 두면 학생이 제출을 놓친다.
		assertThat(response.current().roundNo()).isEqualTo(6);
		// 마감이 지난 OPEN 회차는 더 낼 수 없으므로 지난 구획으로 간다 — 버리지 않는다.
		assertThat(response.past()).extracting("roundNo").containsExactly(5, 4);
		assertThat(response.upcoming()).isEmpty();
	}

	@Test
	void keepsEveryRoundInExactlyOneSection() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 5, Instant.parse("2026-08-01T09:00:00Z")),
				round("OPEN", 6, Instant.parse("2026-08-21T09:00:00Z")),
				round("OPEN", 7, Instant.parse("2026-08-28T09:00:00Z")),
				round("PLANNED", 8, Instant.parse("2026-09-04T09:00:00Z")),
				round("COMPLETED", 4, Instant.parse("2026-07-17T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 불변식: 조회된 회차 수 = current(1) + upcoming + past.
		int placed = 1 + response.upcoming().size() + response.past().size();
		assertThat(placed).isEqualTo(5);
		assertThat(response.current().roundNo()).isEqualTo(6);
		assertThat(response.upcoming()).extracting("roundNo").containsExactly(7, 8);
		assertThat(response.past()).extracting("roundNo").containsExactly(5, 4);
	}

	@Test
	void fallsBackToLatestOpenRoundWhenEverySubmissionDeadlineHasPassed() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 4, Instant.parse("2026-07-17T09:00:00Z")),
				round("OPEN", 5, Instant.parse("2026-08-01T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 전부 마감이 지났으면 가장 최근 회차가 학생이 보고 싶은 것이다.
		assertThat(response.current().roundNo()).isEqualTo(5);
		assertThat(response.past()).extracting("roundNo").containsExactly(4);
	}

	@Test
	void sortsPastByProjectSequenceNoWhenEveryRoundNoIsOne() {
		authenticated();
		// 미니프로젝트 실데이터 모양 — round_no가 프로젝트 안에서만 유일해 전부 1이다.
		// DB가 준 순서(1 · 4 · 3 · 2)를 그대로 통과시키면 안 된다(24차 R5).
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				baseRound("COMPLETED", 1, 1, Instant.parse("2026-03-20T09:00:00Z"), null, null),
				baseRound("COMPLETED", 1, 4, Instant.parse("2026-07-17T09:00:00Z"), null, null),
				baseRound("COMPLETED", 1, 3, Instant.parse("2026-05-29T09:00:00Z"), null, null),
				baseRound("COMPLETED", 1, 2, Instant.parse("2026-04-24T09:00:00Z"), null, null)
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.past()).extracting("roundName")
				.containsExactly("미프 4차", "미프 3차", "미프 2차", "미프 1차");
	}

	@Test
	void synthesizesNoActiveRoundCardWhenNoRoundIsOpen() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("PLANNED", 4, Instant.parse("2026-07-21T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 예정 회차가 있어도 "지금" 할 일은 없다.
		assertThat(response.current().representativeStatus()).isEqualTo("NO_ACTIVE_ROUND");
		assertThat(response.current().defaultActionCode()).isEqualTo("NONE");
		assertThat(response.current().assessmentRoundId()).isNull();
		assertThat(response.upcoming()).hasSize(1);
	}

	@Test
	void returnsSynthesizedCardWithEmptyMembershipWhenTraineeBelongsToNoCohort() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of());
		when(repository.findMembershipByUserId(traineeUserId)).thenReturn(Optional.empty());

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 기수 미소속은 404가 아니라 200 + NO_ACTIVE_ROUND다.
		assertThat(response.current().representativeStatus()).isEqualTo("NO_ACTIVE_ROUND");
		assertThat(response.membership().cohortId()).isNull();
		assertThat(response.membership().className()).isNull();
		assertThat(response.upcoming()).isEmpty();
		assertThat(response.past()).isEmpty();
	}

	@Test
	void fallsBackToCohortMembershipOnlyWhenNoRoundCardExists() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of());
		when(repository.findMembershipByUserId(traineeUserId)).thenReturn(Optional.of(
				new TraineeMembership(UUID.randomUUID(), "7기", UUID.randomUUID(), "A반")
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.membership().cohortName()).isEqualTo("7기");
		assertThat(response.membership().className()).isEqualTo("A반");
	}

	@Test
	void readsMembershipFromRoundCardWithoutExtraQuery() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 3, Instant.parse("2026-07-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.membership().cohortName()).isEqualTo("7기");
		// 회차 카드가 기수·반을 이미 담고 있으므로 대체 조회로 내려가지 않는다.
		verify(repository, never()).findMembershipByUserId(traineeUserId);
	}

	@Test
	void keepsTeamOnCurrentCardNotOnMembership() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 3, Instant.parse("2026-07-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 팀은 회차마다 바뀌므로 기수 스코프인 membership에 두지 않는다.
		assertThat(response.current().teamName()).isEqualTo("3팀");
		assertThat(response.current().teamNumber()).isEqualTo("3");
	}

	@Test
	void normalizesMissingTraineeReleaseStatusToNotConfigured() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				roundWithReport("OPEN", 3, null)
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 리포트 행이 없으면 View가 NULL을 주지만 클라이언트 분기를 하나로 유지한다.
		assertThat(response.current().traineeReleaseStatus()).isEqualTo("NOT_CONFIGURED");
		assertThat(response.current().canViewReport()).isFalse();
	}

	@Test
	void allowsReportViewOnlyWhenTraineeReleaseStatusIsReleased() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				roundWithReport("CLOSED", 2, "RELEASED"),
				roundWithReport("CLOSED", 1, "WITHHELD")
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.past()).extracting("canViewReport").containsExactly(true, false);
	}

	@Test
	void fallsBackToGithubOnlyWhenOrganizationHasNoActivePolicy() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(traineeUserId);
		when(repository.findSubmissionMethodPolicyByUserId(traineeUserId)).thenReturn(Optional.empty());
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 3, Instant.parse("2026-07-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.current().availableSubmissionMethods()).containsExactly("GITHUB_URL");
	}

	@Test
	void omitsZipMethodWhenOrganizationDisallowsIt() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(traineeUserId);
		when(repository.findSubmissionMethodPolicyByUserId(traineeUserId))
				.thenReturn(Optional.of(new SubmissionMethodPolicy(true, false)));
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 3, Instant.parse("2026-07-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		assertThat(response.current().availableSubmissionMethods()).containsExactly("GITHUB_URL");
	}

	@Test
	void passesViewContractValuesThroughUntouched() {
		authenticated();
		when(repository.findAllByTraineeUserId(traineeUserId)).thenReturn(List.of(
				round("OPEN", 3, Instant.parse("2026-07-14T09:00:00Z"))
		));

		AssessmentRoundsResponse response = service.getMyAssessmentRounds();

		// 상태 판정은 View가 끝냈으므로 서버가 다시 파생시키지 않는다.
		assertThat(response.current().representativeStatus()).isEqualTo("ANALYZING");
		assertThat(response.current().defaultActionCode()).isEqualTo("WAIT_FOR_ANALYSIS");
		assertThat(response.current().analysisPhase()).isEqualTo("ANALYZING");
		assertThat(response.current().commitEmailStatus()).isEqualTo("PENDING");
	}

	/**
	 * 회차 하나. <b>{@code roundNo} 자리에 넘긴 값이 {@code projectSequenceNo}로도 들어간다</b> —
	 * 미니프로젝트 실데이터에서는 {@code roundNo}가 전부 1이지만, 여기서는 어느 회차인지
	 * 알아보기 쉽도록 차수를 그대로 쓴다. 정렬 축이 둘 중 무엇인지 가리는 검증은
	 * {@link #sortsPastByProjectSequenceNoWhenEveryRoundNoIsOne}이 따로 한다.
	 */
	private static TraineeHomeRound round(String roundStatus, int roundNo, Instant submissionDueAt) {
		return baseRound(roundStatus, roundNo, roundNo, submissionDueAt, null, null);
	}

	private static TraineeHomeRound roundWithReport(String roundStatus, int roundNo, String traineeReleaseStatus) {
		return baseRound(roundStatus, roundNo, roundNo, Instant.parse("2026-07-14T09:00:00Z"),
				UUID.randomUUID(), traineeReleaseStatus);
	}

	private static TraineeHomeRound baseRound(
			String roundStatus,
			int roundNo,
			int projectSequenceNo,
			Instant submissionDueAt,
			UUID reportId,
			String traineeReleaseStatus
	) {
		return new TraineeHomeRound(
				UUID.randomUUID(), roundNo, projectSequenceNo, "미프 " + projectSequenceNo + "차", roundStatus,
				UUID.randomUUID(), "미프 " + projectSequenceNo + "차", "MINI_PROJECT", List.of("AI_LLMOps"),
				UUID.randomUUID(), "7기", UUID.randomUUID(), "A반", UUID.randomUUID(), "3", "3팀",
				"ANALYZING", "WAIT_FOR_ANALYSIS", null, List.of(),
				"PENDING", "GITHUB_URL", "ACCEPTED", Instant.parse("2026-07-14T08:22:10Z"), true, true,
				"ANALYZING", "RUNNING", null,
				"ANALYZING", null, 3, null, 1,
				reportId, "NOT_PUBLISHED", traineeReleaseStatus, "UNAVAILABLE",
				submissionDueAt, null, null, null, null, null, "ROUND_BATCH", null,
				UUID.randomUUID(), "김매니저", AS_OF
		);
	}
}
