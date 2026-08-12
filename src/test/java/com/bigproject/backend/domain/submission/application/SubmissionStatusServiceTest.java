package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository.MemberRow;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository.RequirementResultRow;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository.RequirementRow;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository.RoundScope;
import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository.TeamRow;
import com.bigproject.backend.domain.submission.presentation.dto.ProjectSubmissionStatusResponse;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 화면이 하지 않기로 한 판정들을 고정한다.
 *
 * <p>특히 응시 상태 넷과 팀 편성 단계다. 이 둘이 흔들리면 화면은 표를 그릴지 빈 상태를 보여줄지,
 * 어떤 사람을 독촉해야 하는지를 스스로 유추하기 시작한다.
 */
class SubmissionStatusServiceTest {

	private static final String EMAIL = "manager@example.com";
	private static final UUID PROJECT_ID = UUID.randomUUID();
	private static final UUID ROUND_ID = UUID.randomUUID();
	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID COHORT_ID = UUID.randomUUID();
	private static final UUID CLASS_ID = UUID.randomUUID();
	private static final UUID TEAM_ID = UUID.randomUUID();
	private static final UUID OTHER_TEAM_ID = UUID.randomUUID();

	private SubmissionStatusQueryRepository repository;
	private ManagerViewScopeGuard scopeGuard;
	private SubmissionStatusService service;

	@BeforeEach
	void setUp() {
		repository = mock(SubmissionStatusQueryRepository.class);
		scopeGuard = mock(ManagerViewScopeGuard.class);
		service = new SubmissionStatusService(repository, scopeGuard);

		when(scopeGuard.requireCohort(any(), any()))
				.thenReturn(new ManagerViewScopeGuard.ManagerActor(UUID.randomUUID(), ORG_ID));
		when(repository.findRound(PROJECT_ID, 1)).thenReturn(Optional.of(round("RUNNING")));
		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of());
		when(repository.findMembers(any(), any(), any())).thenReturn(List.of());
		when(repository.findRequirements(any(), any())).thenReturn(List.of());
		when(repository.findRequirementResults(any(), any(), any())).thenReturn(List.of());
		when(repository.countUnassignedMembers(any(), any(), any())).thenReturn(0L);
	}

	@Test
	void failsWhenTheRoundDoesNotExist() {
		when(repository.findRound(PROJECT_ID, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findSubmissionStatus(EMAIL, PROJECT_ID, 9, null))
				.isInstanceOf(SubmissionException.class)
				.extracting(exception -> ((SubmissionException) exception).errorCode())
				.isEqualTo(SubmissionErrorCode.PROJECT_ROUND_NOT_FOUND);
	}

	@Test
	void splitsAttendanceIntoFourStates() {
		Instant now = Instant.now();
		UUID doneUser = UUID.randomUUID();
		UUID openUser = UUID.randomUUID();
		UUID missedUser = UUID.randomUUID();
		UUID blockedUser = UUID.randomUUID();

		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findMembers(any(), any(), any())).thenReturn(List.of(
				member(doneUser, "가", "COMPLETED", "COMPLETED",
						now.plus(1, ChronoUnit.DAYS), now.minus(2, ChronoUnit.HOURS)),
				member(openUser, "나", "NOT_STARTED", "NOT_STARTED",
						now.plus(1, ChronoUnit.DAYS), null),
				member(missedUser, "다", "NOT_STARTED", "NOT_STARTED",
						now.minus(1, ChronoUnit.DAYS), null),
				// 수행 자체가 만들어지지 않았다 -- 미제출·분석 실패로 응시 창이 열리지 않은 사람이다.
				new MemberRow(TEAM_ID, blockedUser, "라", null, null, "NOT_STARTED", null, null, null)
		));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teams()).hasSize(1);
		assertThat(response.teams().get(0).members())
				.extracting(ProjectSubmissionStatusResponse.Member::userId,
						ProjectSubmissionStatusResponse.Member::attendanceStatus)
				.containsExactly(
						tuple(doneUser, "DONE"),
						tuple(openUser, "OPEN"),
						tuple(missedUser, "MISSED"),
						tuple(blockedUser, "BLOCKED"));
	}

	@Test
	void countsSubmittedTeamsByTimestampNotByRecordExistence() {
		// 접수 중(submittedAt이 아직 없는) 제출을 '냈다'로 세면 탭 머리의 6/8이 틀린다.
		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of(
				submittedTeam(),
				new TeamRow(OTHER_TEAM_ID, CLASS_ID, "A반", "4", "4팀", "CONFIRMED",
						null, null, null, null, null, null, null,
						null, null, null, null)));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.summary().teamCount()).isEqualTo(2);
		assertThat(response.summary().submittedTeamCount()).isEqualTo(1);
		assertThat(response.summary().unsubmittedTeamCount()).isEqualTo(1);
		assertThat(response.teams().get(1).submission()).isNull();
		assertThat(response.teams().get(1).analysis()).isNull();
	}

	@Test
	void keepsSubmissionClosedWhileMembersAreStillUnassigned() {
		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.countUnassignedMembers(any(), any(), any())).thenReturn(3L);

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teamFormationStage()).isEqualTo("FORMING");
		assertThat(response.submissionOpened()).isFalse();
		assertThat(response.unassignedMemberCount()).isEqualTo(3L);
	}

	@Test
	void marksAClosedProjectAsLockedAndStillOpen() {
		// 끝난 회차도 제출 현황은 읽을 수 있어야 한다 -- 잠기는 것은 편성 액션이지 조회가 아니다.
		when(repository.findRound(PROJECT_ID, 1)).thenReturn(Optional.of(round("CLOSED")));
		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teamFormationStage()).isEqualTo("CLOSED");
		assertThat(response.submissionOpened()).isTrue();
		assertThat(response.locked()).isTrue();
	}

	@Test
	void ordersRequirementResultsBySequenceNo() {
		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findRequirements(any(), any())).thenReturn(List.of(
				new RequirementRow(UUID.randomUUID(), "HITL", 1, "HITL 트리거", "설명"),
				new RequirementRow(UUID.randomUUID(), "STATE", 2, "State 갱신", "설명")));
		when(repository.findRequirementResults(any(), any(), any())).thenReturn(List.of(
				new RequirementResultRow(TEAM_ID, UUID.randomUUID(), "STATE", "State 갱신", 2,
						"FAIL", "update_state를 부르는 곳이 없음", true),
				new RequirementResultRow(TEAM_ID, UUID.randomUUID(), "HITL", "HITL 트리거", 1,
						"PASS", "hitl/trigger.py:14-22", true)));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.requirements()).hasSize(2);
		assertThat(response.teams().get(0).requirementResults())
				.extracting(ProjectSubmissionStatusResponse.RequirementResult::requirementKey)
				.containsExactly("HITL", "STATE");
	}

	@Test
	void dropsMembersWhoAreNotOnAnyTeam() {
		// 미배정 인원은 팀 그룹 아래에 그릴 자리가 없다. 팀이 null인 행을 그대로 두면 NPE로 터진다.
		when(repository.findTeams(any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findMembers(any(), any(), any())).thenReturn(List.of(
				new MemberRow(null, UUID.randomUUID(), "미배정", null, null, "NOT_STARTED", null, null, null)));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teams().get(0).members()).isEmpty();
	}

	private RoundScope round(String lifecycleStatus) {
		return new RoundScope(ROUND_ID, PROJECT_ID, ORG_ID, COHORT_ID, "미프 3차", 1, "3차",
				Instant.parse("2026-07-14T14:59:00Z"), lifecycleStatus);
	}

	private TeamRow submittedTeam() {
		return new TeamRow(TEAM_ID, CLASS_ID, "A반", "3", "3팀", "CONFIRMED",
				UUID.randomUUID(), Instant.parse("2026-07-13T14:55:00Z"),
				"https://github.com/team-a/mif3-3", UUID.randomUUID(), "김민준",
				"GITHUB_URL", "ACCEPTED",
				UUID.randomUUID(), "SUCCEEDED", null, null);
	}

	private MemberRow member(UUID userId, String name, String attemptStatus, String completionStatus,
			Instant closeAt, Instant completedAt) {
		return new MemberRow(TEAM_ID, userId, name, UUID.randomUUID(), attemptStatus, completionStatus,
				closeAt.minus(1, ChronoUnit.DAYS), closeAt, completedAt);
	}
}
