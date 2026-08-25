package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import com.bigproject.backend.domain.academicoperations.domain.ManagerAssignment;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectExecutionErrorCode;
import com.bigproject.backend.domain.projectexecution.domain.Team;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팀 편성의 <b>반 스코프</b>. 그린컴퍼니 7기에서 실제로 난 두 가지를 못 박는다.
 *
 * <ol>
 *   <li>미배정 목록이 기수 전원(249명)이었다 — 같은 응답의 {@code teams[]}는 담당 반만 주는데
 *       미배정만 전체라, 배너가 늘 켜져 있고 사람을 넣어도 줄지 않았다.</li>
 *   <li>자동 배분의 "팀이 하나도 없다" 판정이 프로젝트 전역이었다 — 미프 5차는 J반에 시드 팀이
 *       6개 있어서 <b>B·D반 매니저도 409</b>였다. 남의 반 팀 때문에 내 반을 못 짰다.</li>
 * </ol>
 */
class TeamServiceClassScopeTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COHORT = UUID.randomUUID();
	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID MANAGER = UUID.randomUUID();
	/** 이도윤이 맡은 두 반. */
	private static final UUID CLASS_B = UUID.randomUUID();
	private static final UUID CLASS_D = UUID.randomUUID();
	/** 남의 반. 미프 5차의 시드 팀 6개가 여기 있다. */
	private static final UUID CLASS_J = UUID.randomUUID();

	private TeamRepository teamRepository;
	private TeamMembershipRepository teamMembershipRepository;
	private ProjectMembershipQueryRepository projectMembershipQueryRepository;
	private ProjectRepository projectRepository;
	private ClassroomRepository classroomRepository;
	private ManagerAssignmentRepository managerAssignmentRepository;
	private TeamService service;

	@BeforeEach
	void setUp() {
		teamRepository = mock(TeamRepository.class);
		teamMembershipRepository = mock(TeamMembershipRepository.class);
		projectMembershipQueryRepository = mock(ProjectMembershipQueryRepository.class);
		projectRepository = mock(ProjectRepository.class);
		classroomRepository = mock(ClassroomRepository.class);
		managerAssignmentRepository = mock(ManagerAssignmentRepository.class);

		service = new TeamService(teamRepository, teamMembershipRepository, projectMembershipQueryRepository,
				projectRepository, classroomRepository, managerAssignmentRepository,
				mock(AssessmentAxisQueryRepository.class), mock(SubmissionQueryRepository.class));

		// 🔴 mock 을 만드는 헬퍼를 thenReturn(...) 안에서 부르면 Mockito 가 스터빙이 안 끝난 것으로
		// 본다(UnfinishedStubbingException). 먼저 만들어 두고 넘긴다.
		Project project = project();
		List<ManagerAssignment> assignments = List.of(assignment(CLASS_B), assignment(CLASS_D));

		when(projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(Optional.of(project));
		when(managerAssignmentRepository
				.findByManagerUserIdAndOrgIdAndStatusAndUnassignedAtIsNull(MANAGER, ORG, "ACTIVE"))
				.thenReturn(assignments);
		classroomIn(CLASS_B, COHORT);
		classroomIn(CLASS_D, COHORT);
		classroomIn(CLASS_J, COHORT);
	}

	@Test
	@DisplayName("미배정 목록은 담당 반으로 좁혀 조회한다 — 기수 전원이 아니다")
	void unassignedIsScopedToManagedClasses() {
		service.findUnassignedMembers(PROJECT, ORG, MANAGER, null);

		verify(projectMembershipQueryRepository).findUnassigned(PROJECT, ORG, List.of(CLASS_B, CLASS_D));
	}

	@Test
	@DisplayName("반을 지정하면 그 반만 — 담당 반일 때만 좁혀지고, 남의 반이면 빈 스코프다")
	void unassignedNarrowsToRequestedClassOnlyWhenManaged() {
		service.findUnassignedMembers(PROJECT, ORG, MANAGER, CLASS_B);
		verify(projectMembershipQueryRepository).findUnassigned(PROJECT, ORG, List.of(CLASS_B));

		service.findUnassignedMembers(PROJECT, ORG, MANAGER, CLASS_J);
		verify(projectMembershipQueryRepository).findUnassigned(PROJECT, ORG, List.of());
	}

	@Test
	@DisplayName("남의 반 팀은 내 반 자동 배분을 막지 않는다")
	void otherClassTeamsDoNotBlockAutoAssign() {
		// 미프 5차의 실제 상태: J반에만 팀이 있다.
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG)).thenReturn(List.of(team(CLASS_J)));
		when(projectMembershipQueryRepository.findUnassigned(PROJECT, ORG, List.of(CLASS_B)))
				.thenReturn(List.of(new ProjectMembershipQueryRepository.UnassignedMember(
						UUID.randomUUID(), UUID.randomUUID(), "김민준", CLASS_B)));
		when(teamRepository.saveAndFlush(any(Team.class))).thenAnswer(call -> call.getArgument(0));

		List<Team> created = service.autoAssign(PROJECT, ORG, CLASS_B, 4, false, MANAGER);

		assertThat(created).hasSize(1);
		assertThat(created.get(0).getClassId()).isEqualTo(CLASS_B);
	}

	@Test
	@DisplayName("같은 반에 이미 팀이 있으면 자동 배분을 막는다")
	void ownClassTeamsBlockAutoAssign() {
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG)).thenReturn(List.of(team(CLASS_B)));

		assertThatThrownBy(() -> service.autoAssign(PROJECT, ORG, CLASS_B, 4, false, MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.AUTO_ASSIGN_NOT_ALLOWED);

		verify(teamRepository, never()).saveAndFlush(any(Team.class));
	}

	@Test
	@DisplayName("담당하지 않는 반을 지정하면 403이다")
	void rejectsUnmanagedClass() {
		assertThatThrownBy(() -> service.createTeam(PROJECT, ORG, CLASS_J, "1팀", MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.CLASS_NOT_MANAGED);
	}

	@Test
	@DisplayName("다른 기수의 반을 지정하면 404다 — 담당 여부보다 먼저 걸린다")
	void rejectsClassFromAnotherCohort() {
		UUID classInOtherCohort = UUID.randomUUID();
		classroomIn(classInOtherCohort, UUID.randomUUID());

		assertThatThrownBy(() -> service.createTeam(PROJECT, ORG, classInOtherCohort, "1팀", MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.CLASS_NOT_FOUND);
	}

	@Test
	@DisplayName("팀 번호는 그 반 안에서 센다 — 프로젝트 전체 팀 수가 아니다")
	void teamNumberCountsWithinTheClass() {
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG))
				.thenReturn(List.of(team(CLASS_J), team(CLASS_J), team(CLASS_B)));
		when(teamRepository.saveAndFlush(any(Team.class))).thenAnswer(call -> call.getArgument(0));

		Team created = service.createTeam(PROJECT, ORG, CLASS_B, "2팀", MANAGER);

		// J반 2팀은 세지 않는다 — 종전 구현이면 "4"가 나왔다.
		assertThat(created.getTeamNumber()).isEqualTo("2");
	}

	@Test
	@DisplayName("확정은 그 반만 본다 — 다른 반의 미배정이 내 반 확정을 막지 않는다")
	void confirmLooksOnlyAtTheGivenClass() {
		Team mine = team(CLASS_B);
		when(teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(PROJECT, ORG)).thenReturn(List.of(mine, team(CLASS_J)));
		when(projectMembershipQueryRepository.findUnassigned(PROJECT, ORG, List.of(CLASS_B)))
				.thenReturn(List.of());

		service.confirmTeams(PROJECT, ORG, CLASS_B, MANAGER);

		verify(projectMembershipQueryRepository).findUnassigned(PROJECT, ORG, List.of(CLASS_B));
		assertThat(mine.getStatus().name()).isEqualTo("CONFIRMED");
	}

	// ── fixtures ────────────────────────────────────────────────────────────

	private Project project() {
		return Project.createMiniProject(ORG, COHORT, "미프 5차", 5,
				LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4), MANAGER);
	}

	private ManagerAssignment assignment(UUID classId) {
		ManagerAssignment assignment = mock(ManagerAssignment.class);
		when(assignment.getClassId()).thenReturn(classId);
		return assignment;
	}

	private void classroomIn(UUID classId, UUID cohortId) {
		Classroom classroom = mock(Classroom.class);
		when(classroom.getCohortId()).thenReturn(cohortId);
		Optional<Classroom> found = Optional.of(classroom);
		when(classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, ORG)).thenReturn(found);
	}

	private Team team(UUID classId) {
		return Team.builder()
				.projectId(PROJECT)
				.classId(classId)
				.orgId(ORG)
				.teamNumber("1")
				.name("1팀")
				.minMemberCount(1)
				.maxMemberCount(6)
				.createdBy(MANAGER)
				.build();
	}
}
