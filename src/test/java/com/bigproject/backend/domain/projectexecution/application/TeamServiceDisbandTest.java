package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import com.bigproject.backend.domain.academicoperations.domain.ManagerAssignment;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectExecutionErrorCode;
import com.bigproject.backend.domain.projectexecution.domain.Team;
import com.bigproject.backend.domain.projectexecution.domain.TeamMembership;
import com.bigproject.backend.domain.projectexecution.infrastructure.AssessmentAxisQueryRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectMembershipQueryRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.SubmissionQueryRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.TeamMembershipRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.TeamRepository;
import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팀 해체가 <b>읽기에도 반영되는가</b>(46차 R1·R2).
 *
 * <p>해체는 행을 지우지 않고 {@code deleted_at}만 찍는다. 그 자체는 의도한 설계인데
 * ({@code TeamService#disbandTeam} 참고) 조회 쪽에 필터가 하나도 없어서, 매니저가 보기에는
 * <b>204를 받았는데 아무 일도 안 일어난 것</b>과 구분되지 않았다.
 *
 * <ol>
 *   <li>목록에 해체한 팀이 그대로 남았다</li>
 *   <li>같은 이름으로 다시 만들면 살아 있는 행과 부딪혀 409였다</li>
 *   <li>팀을 전부 해체한 반은 자동 배분이 "이미 팀이 있다"로 영영 막혔다</li>
 *   <li>번호를 개수로 세서, 가운데 팀을 해체한 반은 다음 번호가 살아 있는 팀과 겹쳤다</li>
 * </ol>
 *
 * <p>이 스펙이 못 박는 것은 <b>해체된 팀은 조회에 존재하지 않는다</b> 하나다.
 */
class TeamServiceDisbandTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COHORT = UUID.randomUUID();
	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID MANAGER = UUID.randomUUID();
	/** 재현에 쓴 E반. */
	private static final UUID CLASS_E = UUID.randomUUID();
	/** 재현에 쓴 "2팀". 팀 엔티티의 ID는 영속화 전에는 null이라 조회 키는 따로 둔다. */
	private static final UUID TEAM_ID = UUID.randomUUID();

	private TeamRepository teamRepository;
	private TeamMembershipRepository teamMembershipRepository;
	private ProjectMembershipQueryRepository projectMembershipQueryRepository;
	private SubmissionQueryRepository submissionQueryRepository;
	private ClassroomRepository classroomRepository;
	private TeamService service;

	@BeforeEach
	void setUp() {
		teamRepository = mock(TeamRepository.class);
		teamMembershipRepository = mock(TeamMembershipRepository.class);
		projectMembershipQueryRepository = mock(ProjectMembershipQueryRepository.class);
		submissionQueryRepository = mock(SubmissionQueryRepository.class);
		ProjectRepository projectRepository = mock(ProjectRepository.class);
		classroomRepository = mock(ClassroomRepository.class);
		ManagerAssignmentRepository managerAssignmentRepository = mock(ManagerAssignmentRepository.class);

		service = new TeamService(teamRepository, teamMembershipRepository, projectMembershipQueryRepository,
				projectRepository, classroomRepository, managerAssignmentRepository,
				mock(AssessmentAxisQueryRepository.class), submissionQueryRepository);

		Project project = project();
		ManagerAssignment assignment = mock(ManagerAssignment.class);
		when(assignment.getClassId()).thenReturn(CLASS_E);
		Classroom classroom = mock(Classroom.class);
		when(classroom.getCohortId()).thenReturn(COHORT);

		when(projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(Optional.of(project));
		when(managerAssignmentRepository
				.findByManagerUserIdAndOrgIdAndStatusAndUnassignedAtIsNull(MANAGER, ORG, "ACTIVE"))
				.thenReturn(List.of(assignment));
		when(classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(CLASS_E, ORG))
				.thenReturn(Optional.of(classroom));
	}

	@Test
	@DisplayName("해체는 deleted_at을 찍고 팀원의 배정을 끝낸다")
	void disbandMarksDeletedAndUnassignsMembers() {
		Team team = team(CLASS_E, "2", "2팀");
		when(teamRepository.findByTeamIdAndOrgIdAndDeletedAtIsNull(TEAM_ID, ORG))
				.thenReturn(Optional.of(team));
		when(submissionQueryRepository.hasAcceptedSubmission(TEAM_ID, ORG)).thenReturn(false);
		when(teamMembershipRepository.findByTeamIdAndOrgIdAndToAtIsNull(TEAM_ID, ORG))
				.thenReturn(List.of());

		service.disbandTeam(TEAM_ID, ORG, MANAGER);

		assertThat(team.isDisbanded()).isTrue();
	}

	@Test
	@DisplayName("해체된 팀은 목록에서 사라진다 — 저장소가 살아 있는 팀만 준다")
	void disbandedTeamsLeaveTheList() {
		Team alive = team(CLASS_E, "1", "1팀");
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG)).thenReturn(List.of(alive));

		List<Team> teams = service.findManagedTeams(PROJECT, ORG, MANAGER, CLASS_E);

		assertThat(teams).containsExactly(alive);
		verify(teamRepository).findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG);
	}

	@Test
	@DisplayName("해체된 팀에 다시 손대면 404다 — 목록에 없는 팀은 없는 팀이다")
	void disbandedTeamIsNotFound() {
		UUID gone = UUID.randomUUID();
		when(teamRepository.findByTeamIdAndOrgIdAndDeletedAtIsNull(gone, ORG)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.disbandTeam(gone, ORG, MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_NOT_FOUND);
	}

	@Test
	@DisplayName("팀을 전부 해체한 반은 자동 배분을 다시 쓸 수 있다")
	void autoAssignIsAllowedAfterEveryTeamIsDisbanded() {
		// 해체된 팀만 남은 반 = 저장소가 빈 목록을 준다.
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG)).thenReturn(List.of());
		when(projectMembershipQueryRepository.findUnassigned(PROJECT, ORG, List.of(CLASS_E)))
				.thenReturn(List.of(new ProjectMembershipQueryRepository.UnassignedMember(
						UUID.randomUUID(), UUID.randomUUID(), "김민준", CLASS_E)));
		when(teamRepository.saveAndFlush(any(Team.class))).thenAnswer(call -> call.getArgument(0));

		List<Team> created = service.autoAssign(PROJECT, ORG, CLASS_E, 4, false, MANAGER);

		assertThat(created).hasSize(1);
		assertThat(created.get(0).getClassId()).isEqualTo(CLASS_E);
	}

	@Test
	@DisplayName("번호는 개수가 아니라 최대 번호 + 1이다 — 가운데를 해체해도 겹치지 않는다")
	void teamNumberFollowsHighestLivingNumber() {
		// 1·2·3을 만들고 2팀을 해체한 반: 살아 있는 팀은 1·3이다.
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(List.of(team(CLASS_E, "1", "1팀"), team(CLASS_E, "3", "3팀")));
		when(teamRepository.saveAndFlush(any(Team.class))).thenAnswer(call -> call.getArgument(0));

		Team created = service.createTeam(PROJECT, ORG, CLASS_E, "4팀", MANAGER);

		// 개수(2) + 1 = "3"이면 살아 있는 3팀과 uq_team_..._team_number가 부딪힌다.
		assertThat(created.getTeamNumber()).isEqualTo("4");
	}

	@Test
	@DisplayName("정상 접수된 제출이 있으면 해체를 막는다 — 그 규칙은 그대로다")
	void submittedTeamStaysLocked() {
		Team team = team(CLASS_E, "1", "1팀");
		when(teamRepository.findByTeamIdAndOrgIdAndDeletedAtIsNull(TEAM_ID, ORG))
				.thenReturn(Optional.of(team));
		when(submissionQueryRepository.hasAcceptedSubmission(TEAM_ID, ORG)).thenReturn(true);

		assertThatThrownBy(() -> service.disbandTeam(TEAM_ID, ORG, MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED);

		assertThat(team.isDisbanded()).isFalse();
		verify(teamMembershipRepository, never()).findByTeamIdAndOrgIdAndToAtIsNull(any(), any());
	}

	// ── 반 통째로 해체(46차 R3) ──────────────────────────────────────────────

	@Test
	@DisplayName("반 통째로 해체하면 그 반 팀만 전부 해체되고 인원이 미배정으로 돌아간다")
	void disbandClassTeamsClearsTheWholeClass() {
		UUID id1 = UUID.randomUUID();
		UUID id2 = UUID.randomUUID();
		Team first = teamWithId(id1, CLASS_E, "1", "1팀");
		Team second = teamWithId(id2, CLASS_E, "2", "2팀");
		// 남의 반 팀. 같은 프로젝트에 있지만 건드리면 안 된다.
		Team otherClass = teamWithId(UUID.randomUUID(), UUID.randomUUID(), "1", "1팀");

		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(List.of(first, second, otherClass));
		when(submissionQueryRepository.findTeamIdsWithAcceptedSubmission(List.of(id1, id2), ORG))
				.thenReturn(Set.of());
		when(teamMembershipRepository.findByTeamIdAndOrgIdAndToAtIsNull(id1, ORG))
				.thenReturn(List.of(membership(), membership(), membership()));
		when(teamMembershipRepository.findByTeamIdAndOrgIdAndToAtIsNull(id2, ORG))
				.thenReturn(List.of(membership(), membership()));

		TeamService.DisbandResult result = service.disbandClassTeams(PROJECT, ORG, CLASS_E, MANAGER);

		assertThat(result.disbandedTeamCount()).isEqualTo(2);
		assertThat(result.unassignedMemberCount()).isEqualTo(5);
		assertThat(first.isDisbanded()).isTrue();
		assertThat(second.isDisbanded()).isTrue();
		assertThat(otherClass.isDisbanded()).isFalse();
	}

	@Test
	@DisplayName("이미 빈 반은 에러가 아니라 0건이다 — 다시 눌러도 같은 답이다")
	void emptyClassIsSuccessNotError() {
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG)).thenReturn(List.of());

		TeamService.DisbandResult result = service.disbandClassTeams(PROJECT, ORG, CLASS_E, MANAGER);

		assertThat(result.disbandedTeamCount()).isZero();
		assertThat(result.unassignedMemberCount()).isZero();
	}

	@Test
	@DisplayName("확정된 팀이 섞여 있으면 한 팀도 해체하지 않는다 — 편성 다시 열기가 먼저다")
	void confirmedClassIsRejectedWholesale() {
		Team draft = teamWithId(UUID.randomUUID(), CLASS_E, "1", "1팀");
		Team confirmed = teamWithId(UUID.randomUUID(), CLASS_E, "2", "2팀");
		confirmed.confirm();
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(List.of(draft, confirmed));

		assertThatThrownBy(() -> service.disbandClassTeams(PROJECT, ORG, CLASS_E, MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_CONFIRMED_LOCKED);

		assertThat(draft.isDisbanded()).isFalse();
		assertThat(confirmed.isDisbanded()).isFalse();
	}

	@Test
	@DisplayName("제출한 팀이 하나라도 있으면 반 전체가 그대로 남고, 걸린 팀 이름을 말해 준다")
	void submittedTeamBlocksTheWholeClass() {
		UUID clean = UUID.randomUUID();
		UUID locked = UUID.randomUUID();
		Team cleanTeam = teamWithId(clean, CLASS_E, "1", "1팀");
		Team lockedTeam = teamWithId(locked, CLASS_E, "3", "3팀");

		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(List.of(cleanTeam, lockedTeam));
		when(submissionQueryRepository.findTeamIdsWithAcceptedSubmission(List.of(clean, locked), ORG))
				.thenReturn(Set.of(locked));

		assertThatThrownBy(() -> service.disbandClassTeams(PROJECT, ORG, CLASS_E, MANAGER))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("3팀")
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED);

		// 🔴 부분 해체가 없어야 한다 — 걸린 팀을 만나기 전에 지나간 팀도 그대로다.
		assertThat(cleanTeam.isDisbanded()).isFalse();
		assertThat(lockedTeam.isDisbanded()).isFalse();
		verify(teamMembershipRepository, never()).findByTeamIdAndOrgIdAndToAtIsNull(any(), any());
	}

	@Test
	@DisplayName("남의 반은 비울 수 없다 — 팀을 조회하기도 전에 403이다")
	void rejectsUnmanagedClass() {
		// 같은 기수의 반이지만 이 매니저의 담당이 아니다.
		UUID othersClass = UUID.randomUUID();
		Classroom classroom = mock(Classroom.class);
		when(classroom.getCohortId()).thenReturn(COHORT);
		when(classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(othersClass, ORG))
				.thenReturn(Optional.of(classroom));

		assertThatThrownBy(() -> service.disbandClassTeams(PROJECT, ORG, othersClass, MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.CLASS_NOT_MANAGED);

		verify(teamRepository, never()).findByProjectIdAndOrgIdAndDeletedAtIsNull(any(), any());
	}

	// ── fixtures ────────────────────────────────────────────────────────────

	private Project project() {
		return Project.createMiniProject(ORG, COHORT, "미프 5차", 5,
				LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4), MANAGER);
	}

	private Team team(UUID classId, String teamNumber, String name) {
		return Team.builder()
				.projectId(PROJECT)
				.classId(classId)
				.orgId(ORG)
				.teamNumber(teamNumber)
				.name(name)
				.minMemberCount(1)
				.maxMemberCount(6)
				.createdBy(MANAGER)
				.build();
	}

	/**
	 * team_id는 영속화 시점에 DB가 채우므로 빌더로는 비어 있다. 일괄 해체는 팀을 ID로
	 * 구분해 다루므로(제출 조회·팀원 조회) 여기서만 미리 박아 둔다.
	 */
	private Team teamWithId(UUID teamId, UUID classId, String teamNumber, String name) {
		Team team = team(classId, teamNumber, name);
		ReflectionTestUtils.setField(team, "teamId", teamId);
		return team;
	}

	private TeamMembership membership() {
		return mock(TeamMembership.class);
	}
}
