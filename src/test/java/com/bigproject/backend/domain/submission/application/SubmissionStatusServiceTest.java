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
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
	private static final UUID FAILED_TEAM_ID = UUID.randomUUID();
	private static final UUID MANAGER_ID = UUID.randomUUID();
	private static final UUID UNMANAGED_CLASS_ID = UUID.randomUUID();

	private SubmissionStatusQueryRepository repository;
	private ManagerViewScopeGuard scopeGuard;
	private SubmissionStatusService service;

	@BeforeEach
	void setUp() {
		repository = mock(SubmissionStatusQueryRepository.class);
		scopeGuard = mock(ManagerViewScopeGuard.class);
		service = new SubmissionStatusService(repository, scopeGuard);

		when(scopeGuard.requireCohort(any(), any()))
				.thenReturn(new ManagerViewScopeGuard.ManagerActor(MANAGER_ID, ORG_ID));
		when(repository.findRound(PROJECT_ID, 1)).thenReturn(Optional.of(round("RUNNING")));
		when(repository.isClassManagedBy(any(), any(), any())).thenReturn(true);
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of());
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of());
		when(repository.findRequirements(any(), any())).thenReturn(List.of());
		when(repository.findRequirementResults(any(), any(), any(), any())).thenReturn(List.of());
		when(repository.countUnassignedMembers(any(), any(), any(), any())).thenReturn(0L);
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

		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
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
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(
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

	/**
	 * 32차 R11 — <b>편성이 끝나지 않아도 들어온 제출은 보여준다.</b>
	 *
	 * <p>종전에는 이 자리가 {@code submissionOpened=false}였다. 그런데 서버는 제출을 받을 때
	 * 팀 상태를 보지 않으므로({@code SubmissionService.requireSubmittableRound}) 편성 중에도
	 * 제출이 들어오고, 그때 화면이 스펙대로 이 값만 보고 표를 가려 <b>제출·분석·응시가 통째로
	 * 사라졌다</b>. 편성 진행 상황은 {@code teamFormationStage}가 따로 답한다.
	 */
	@Test
	void showsSubmissionsThatArrivedWhileMembersAreStillUnassigned() {
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.countUnassignedMembers(any(), any(), any(), any())).thenReturn(3L);

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teamFormationStage()).isEqualTo("FORMING");
		assertThat(response.submissionOpened()).isTrue();
		assertThat(response.summary().submittedTeamCount()).isEqualTo(1);
		// 미배정 인원은 팀 행 아래가 아니라 이 값으로 나간다 — 화면에서 사라지지 않는다.
		assertThat(response.unassignedMemberCount()).isEqualTo(3L);
	}

	/**
	 * 32차 R11 — 그릴 것이 없을 때는 그대로 빈 상태다. 기준을 넓힌 것이지 없앤 것이 아니다.
	 */
	@Test
	void keepsTheEmptyStateWhenThereIsNothingToDraw() {
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of());

		var noTeams = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(noTeams.teamFormationStage()).isEqualTo("NOT_STARTED");
		assertThat(noTeams.submissionOpened()).isFalse();

		// 시작 전 회차는 팀이 있어도 제출이 있을 수 없다.
		when(repository.findRound(PROJECT_ID, 1)).thenReturn(Optional.of(round("PLANNED")));
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));

		var notStarted = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(notStarted.submissionOpened()).isFalse();
	}

	@Test
	void marksAClosedProjectAsLockedAndStillOpen() {
		// 끝난 회차도 제출 현황은 읽을 수 있어야 한다 -- 잠기는 것은 편성 액션이지 조회가 아니다.
		when(repository.findRound(PROJECT_ID, 1)).thenReturn(Optional.of(round("CLOSED")));
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teamFormationStage()).isEqualTo("CLOSED");
		assertThat(response.submissionOpened()).isTrue();
		assertThat(response.locked()).isTrue();
	}

	@Test
	void ordersRequirementResultsBySequenceNo() {
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findRequirements(any(), any())).thenReturn(List.of(
				new RequirementRow(UUID.randomUUID(), "HITL", 1, "HITL 트리거", "설명"),
				new RequirementRow(UUID.randomUUID(), "STATE", 2, "State 갱신", "설명")));
		when(repository.findRequirementResults(any(), any(), any(), any())).thenReturn(List.of(
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
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				new MemberRow(null, UUID.randomUUID(), "미배정", null, null, "NOT_STARTED", null, null, null)));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teams().get(0).members()).isEmpty();
	}

	@Test
	void rejectsAClassTheManagerDoesNotOwn() {
		// 기수 관문을 통과했다고 그 기수의 모든 반을 담당하는 것은 아니다. 빈 결과로 주면
		// 화면이 "팀이 없는 회차"로 읽으므로 404로 끊는다.
		when(repository.isClassManagedBy(MANAGER_ID, UNMANAGED_CLASS_ID, COHORT_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, UNMANAGED_CLASS_ID))
				.isInstanceOf(ApiException.class)
				.extracting(exception -> ((ApiException) exception).errorCode())
				.isEqualTo(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);

		verify(repository, never()).findTeams(any(), any(), any(), any(), any());
	}

	@Test
	void narrowsEveryQueryToTheManagersOwnClasses() {
		// 조회 자체를 담당 반으로 좁히지 않으면, 기수 관문만 통과한 매니저에게 남의 반 교육생 이름이 나간다.
		service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		verify(repository).findTeams(PROJECT_ID, ROUND_ID, ORG_ID, MANAGER_ID, null);
		verify(repository).findMembers(ROUND_ID, ORG_ID, MANAGER_ID, null);
		verify(repository).findRequirementResults(ROUND_ID, ORG_ID, MANAGER_ID, null);
		verify(repository).countUnassignedMembers(PROJECT_ID, ORG_ID, MANAGER_ID, null);
	}

	/**
	 * 36차 R2 — actionItems[]가 teamCount만 주면 어느 팀인지 화면이 알 수 없다.
	 * ANALYSIS_FAILED_TEAMS와 같은 모양으로 UNSUBMITTED_TEAMS도 팀 이름을 실어야 한다.
	 */
	@Test
	void actionItemsCarryTheNamesOfTheTeamsTheyCount() {
		UUID unsubmittedTeamId = UUID.randomUUID();
		UUID failedTeamId = UUID.randomUUID();
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(
				new TeamRow(unsubmittedTeamId, CLASS_ID, "A반", "1", "1팀", "CONFIRMED",
						null, null, null, null, null, null, null,
						null, null, null, null),
				new TeamRow(failedTeamId, CLASS_ID, "A반", "2", "2팀", "CONFIRMED",
						UUID.randomUUID(), Instant.parse("2026-07-13T14:55:00Z"),
						"https://github.com/team-a/mif3-2", UUID.randomUUID(), "김민준",
						"GITHUB_URL", "ACCEPTED",
						UUID.randomUUID(), "FAILED", "BUILD_FAILED", "빌드 실패")));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				member(UUID.randomUUID(), "가", "COMPLETED", "COMPLETED",
						Instant.now().plus(1, ChronoUnit.DAYS), Instant.now())));

		var result = service.findManagerProjectProgress(EMAIL, PROJECT_ID, null);

		assertThat(result.actionItems()).extracting(
						SubmissionStatusService.ManagerProjectProgress.ActionItem::type,
						SubmissionStatusService.ManagerProjectProgress.ActionItem::teamCount)
				.containsExactlyInAnyOrder(
						tuple(SubmissionStatusService.ManagerProjectProgress.ActionItem.UNSUBMITTED_TEAMS, 1),
						tuple(SubmissionStatusService.ManagerProjectProgress.ActionItem.ANALYSIS_FAILED_TEAMS, 1));

		var unsubmittedItem = result.actionItems().stream()
				.filter(item -> item.type().equals(SubmissionStatusService.ManagerProjectProgress.ActionItem.UNSUBMITTED_TEAMS))
				.findFirst().orElseThrow();
		assertThat(unsubmittedItem.teams())
				.extracting(SubmissionStatusService.ManagerProjectProgress.TeamRef::teamId,
						SubmissionStatusService.ManagerProjectProgress.TeamRef::teamName)
				.containsExactly(tuple(unsubmittedTeamId, "1팀"));

		var failedItem = result.actionItems().stream()
				.filter(item -> item.type().equals(SubmissionStatusService.ManagerProjectProgress.ActionItem.ANALYSIS_FAILED_TEAMS))
				.findFirst().orElseThrow();
		assertThat(failedItem.teams())
				.extracting(SubmissionStatusService.ManagerProjectProgress.TeamRef::teamId,
						SubmissionStatusService.ManagerProjectProgress.TeamRef::teamName)
				.containsExactly(tuple(failedTeamId, "2팀"));
	}

	/**
	 * 종료된 회차의 전원이 {@code DONE}으로 나오던 회귀를 막는다.
	 *
	 * <p>{@code completedAt}(= {@code measurement_attempt.terminal_at})은 COMPLETED만이 아니라
	 * FAILED·EXPIRED에서도 채워진다. 그것을 완료 신호로 쓰는 바람에 미응시·중단·미제출·분석 실패가
	 * 전부 완료로 접혔고, 아래 네 사람 모두 {@code DONE}이었다.
	 */
	@Test
	void doesNotTreatFailedOrExpiredAttemptsAsDone() {
		Instant now = Instant.now();
		Instant closed = now.minus(1, ChronoUnit.DAYS);
		Instant terminalAt = now.minus(2, ChronoUnit.HOURS);
		UUID notAttendedUser = UUID.randomUUID();
		UUID incompleteUser = UUID.randomUUID();
		UUID notSubmittedUser = UUID.randomUUID();
		UUID analysisFailedUser = UUID.randomUUID();

		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				member(notAttendedUser, "가", "EXPIRED", "IN_PROGRESS", closed, terminalAt),
				member(incompleteUser, "나", "EXPIRED", "IN_PROGRESS", closed, terminalAt),
				// 미제출은 응시 창 자체가 열리지 않는다 -- close_at이 없다.
				new MemberRow(TEAM_ID, notSubmittedUser, "다", UUID.randomUUID(), "FAILED",
						"IN_PROGRESS", null, null, terminalAt),
				// 분석 실패는 창이 남아 있어도 볼 수 없었던 경우라 MISSED가 아니라 BLOCKED다.
				member(analysisFailedUser, "라", "FAILED", "IN_PROGRESS", closed, terminalAt)
		));

		var response = service.findSubmissionStatus(EMAIL, PROJECT_ID, 1, null);

		assertThat(response.teams().get(0).members())
				.extracting(ProjectSubmissionStatusResponse.Member::userId,
						ProjectSubmissionStatusResponse.Member::attendanceStatus)
				.containsExactly(
						tuple(notAttendedUser, "MISSED"),
						tuple(incompleteUser, "MISSED"),
						tuple(notSubmittedUser, "BLOCKED"),
						tuple(analysisFailedUser, "BLOCKED"));
	}

	/**
	 * 목록의 `응시 N/M`이 종료된 회차에서 언제나 `M/M`이던 회귀를 막는다.
	 * 그린컴퍼니 5기 미프 3차가 실제 197명인데 249/249로 나왔다.
	 */
	@Test
	void countsOnlyCompletedAttemptsAsAssessed() {
		Instant now = Instant.now();
		Instant terminalAt = now.minus(2, ChronoUnit.HOURS);

		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(submittedTeam()));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				member(UUID.randomUUID(), "가", "COMPLETED", "COMPLETED",
						now.minus(1, ChronoUnit.DAYS), terminalAt),
				member(UUID.randomUUID(), "나", "EXPIRED", "IN_PROGRESS",
						now.minus(1, ChronoUnit.DAYS), terminalAt),
				member(UUID.randomUUID(), "다", "FAILED", "IN_PROGRESS",
						now.minus(1, ChronoUnit.DAYS), terminalAt),
				member(UUID.randomUUID(), "라", "EXPIRED", "IN_PROGRESS",
						now.minus(1, ChronoUnit.DAYS), terminalAt)
		));

		var progress = service.findManagerProjectProgress(EMAIL, PROJECT_ID, null).progress();

		assertThat(progress.assessedCount()).isEqualTo(1);
		assertThat(progress.targetTraineeCount()).isEqualTo(4);
	}

	/**
	 * 응시할 방법이 없었던 사람은 분모가 아니다.
	 *
	 * <p>미제출·분석 실패로 문항이 만들어지지 않으면 아무리 독촉해도 그 사람은 응시할 수 없다.
	 * 분모에 남기면 영원히 줄지 않는 숫자가 되고, 그린컴퍼니 5기 미프 3차에서 29명이 그랬다.
	 * 반별 현황의 응시율 분모(응시 대상)와 같은 기준이다.
	 */
	@Test
	void excludesMembersWhoCouldNotAttendFromTheDenominator() {
		Instant now = Instant.now();
		Instant closeAt = now.minus(1, ChronoUnit.DAYS);

		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(
				submittedTeam(),                                  // 분석 성공 -- 응시 대상
				team(OTHER_TEAM_ID, "4팀", null, null),            // 미제출 -- 응시 불가
				team(FAILED_TEAM_ID, "5팀", Instant.parse("2026-07-13T14:55:00Z"), "FAILED")));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				member(UUID.randomUUID(), "가", "COMPLETED", "COMPLETED", closeAt, now),
				member(UUID.randomUUID(), "나", "EXPIRED", "IN_PROGRESS", closeAt, now),
				memberOf(OTHER_TEAM_ID, "다"),
				memberOf(OTHER_TEAM_ID, "라"),
				memberOf(FAILED_TEAM_ID, "마")));

		var progress = service.findManagerProjectProgress(EMAIL, PROJECT_ID, null).progress();

		assertThat(progress.assessedCount()).isEqualTo(1);
		assertThat(progress.targetTraineeCount()).isEqualTo(2);
		assertThat(progress.blockedCount()).isEqualTo(3);
	}

	/**
	 * 마감 전 미제출은 「응시 불가」가 아니다 — 아직 낼 수 있다.
	 *
	 * <p>마감을 보지 않고 제출 유무만으로 세면 진행 중인 회차가 통째로 응시 불가가 된다.
	 * 그린컴퍼니 7기 미프 4차(마감 3일 뒤)에서 249명 전원이 그렇게 잡혔다.
	 */
	@Test
	void doesNotBlockTeamsThatCanStillSubmit() {
		when(repository.findRound(PROJECT_ID, 1))
				.thenReturn(Optional.of(round("RUNNING", Instant.now().plus(3, ChronoUnit.DAYS))));
		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(
				team(TEAM_ID, "3팀", null, null)));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				memberOf(TEAM_ID, "가"),
				memberOf(TEAM_ID, "나")));

		var progress = service.findManagerProjectProgress(EMAIL, PROJECT_ID, null).progress();

		// 아직 정해지지 않았으므로 분모도 응시 불가도 아니다. 그래도 progress는 온다.
		assertThat(progress.targetTraineeCount()).isZero();
		assertThat(progress.blockedCount()).isZero();
		assertThat(progress.assessedCount()).isZero();
	}

	/**
	 * 부분 성공은 분모에 남는다 — 일부 개념만 문항이 생성됐어도 그 문항으로 응시할 수 있다.
	 * 응시할 수 있는 사람을 분모에서 빼면 응시율이 부풀려진다.
	 */
	@Test
	void keepsPartiallyAnalysedMembersInTheDenominator() {
		Instant closeAt = Instant.now().minus(1, ChronoUnit.DAYS);

		when(repository.findTeams(any(), any(), any(), any(), any())).thenReturn(List.of(
				team(TEAM_ID, "3팀", Instant.parse("2026-07-13T14:55:00Z"), "PARTIAL")));
		when(repository.findMembers(any(), any(), any(), any())).thenReturn(List.of(
				member(UUID.randomUUID(), "가", "COMPLETED", "COMPLETED", closeAt, Instant.now()),
				member(UUID.randomUUID(), "나", "EXPIRED", "IN_PROGRESS", closeAt, Instant.now())));

		var progress = service.findManagerProjectProgress(EMAIL, PROJECT_ID, null).progress();

		assertThat(progress.targetTraineeCount()).isEqualTo(2);
		assertThat(progress.blockedCount()).isZero();
	}

	private RoundScope round(String lifecycleStatus) {
		return round(lifecycleStatus, Instant.parse("2026-07-14T14:59:00Z"));
	}

	private RoundScope round(String lifecycleStatus, Instant submissionDueAt) {
		return new RoundScope(ROUND_ID, PROJECT_ID, ORG_ID, COHORT_ID, "미프 3차", 1, "3차",
				submissionDueAt, lifecycleStatus);
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

	/** 미제출·분석 실패 팀의 팀원. 수행이 만들어지지 않아 응시 창도 없다. */
	private MemberRow memberOf(UUID teamId, String name) {
		return new MemberRow(teamId, UUID.randomUUID(), name, null, null, "NOT_STARTED", null, null, null);
	}

	private TeamRow team(UUID teamId, String teamName, Instant submittedAt, String analysisStatus) {
		return new TeamRow(teamId, CLASS_ID, "A반", "3", teamName, "CONFIRMED",
				submittedAt == null ? null : UUID.randomUUID(), submittedAt,
				null, null, null, null, null,
				analysisStatus == null ? null : UUID.randomUUID(), analysisStatus, null, null);
	}
}
