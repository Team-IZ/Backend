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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
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
 * 팀명 충돌이 <b>이유를 말하는 코드</b>로 나가는가(46차).
 *
 * <p>종전에는 UNIQUE 위반이 그대로 DB까지 내려가 전역 처리기의 fallback으로 새 나갔다.
 *
 * <pre>{@code
 * { "status": 409, "code": "DATA_INTEGRITY_VIOLATION",
 *   "message": "요청을 처리할 수 없습니다. 데이터 제약 조건에 맞지 않습니다." }
 * }</pre>
 *
 * <p>화면은 <b>무엇이 잘못됐는지 말할 수 없어</b> 그 문구를 그대로 띄웠고, 매니저는 이름을
 * 바꿔 보면 된다는 것을 알 수 없었다. 이 스펙이 못 박는 것은 두 가지다.
 *
 * <ol>
 *   <li>이름 충돌은 {@code TEAM_NAME_DUPLICATED}, 번호 충돌은 {@code TEAM_NUMBER_DUPLICATED} —
 *       고칠 수 있는 주체가 달라서 화면이 다른 말을 해야 한다</li>
 *   <li>사전 검사를 지나쳐 <b>DB가 끊은 경우도</b> 같은 코드로 나간다 — 동시 요청과
 *       활성 범위 마이그레이션 미적용이 그 경로다</li>
 * </ol>
 */
class TeamServiceNameConflictTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COHORT = UUID.randomUUID();
	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID MANAGER = UUID.randomUUID();
	private static final UUID CLASS_E = UUID.randomUUID();
	private static final UUID TEAM_ID = UUID.randomUUID();

	private TeamRepository teamRepository;
	private TeamService service;

	@BeforeEach
	void setUp() {
		teamRepository = mock(TeamRepository.class);
		ProjectRepository projectRepository = mock(ProjectRepository.class);
		ClassroomRepository classroomRepository = mock(ClassroomRepository.class);
		ManagerAssignmentRepository managerAssignmentRepository = mock(ManagerAssignmentRepository.class);

		service = new TeamService(teamRepository, mock(TeamMembershipRepository.class),
				mock(ProjectMembershipQueryRepository.class), projectRepository, classroomRepository,
				managerAssignmentRepository, mock(AssessmentAxisQueryRepository.class),
				mock(SubmissionQueryRepository.class));

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
	@DisplayName("같은 이름이 이미 있으면 TEAM_NAME_DUPLICATED다 — DB까지 가지 않는다")
	void duplicateNameIsRejectedBeforeInsert() {
		when(teamRepository.existsByProjectIdAndClassIdAndNameAndDeletedAtIsNull(PROJECT, CLASS_E, "2팀"))
				.thenReturn(true);

		assertThatThrownBy(() -> service.createTeam(PROJECT, ORG, CLASS_E, "2팀", MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_NAME_DUPLICATED);

		verify(teamRepository, never()).saveAndFlush(any(Team.class));
	}

	@Test
	@DisplayName("사전 검사를 지나쳐 DB가 이름 제약으로 끊어도 같은 코드로 나간다")
	void databaseNameViolationBecomesTheSameCode() {
		// 동시 요청·활성 범위 마이그레이션 미적용에서 실제로 나는 경로다.
		when(teamRepository.existsByProjectIdAndClassIdAndNameAndDeletedAtIsNull(PROJECT, CLASS_E, "2팀"))
				.thenReturn(false);
		when(teamRepository.saveAndFlush(any(Team.class)))
				.thenThrow(uniqueViolation("uq_team_project_id_class_id_name"));

		assertThatThrownBy(() -> service.createTeam(PROJECT, ORG, CLASS_E, "2팀", MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_NAME_DUPLICATED);
	}

	@Test
	@DisplayName("번호 제약은 TEAM_NUMBER_DUPLICATED다 — 이름을 고치라는 말이 아니다")
	void numberViolationIsItsOwnCode() {
		when(teamRepository.saveAndFlush(any(Team.class)))
				.thenThrow(uniqueViolation("uq_team_project_id_class_id_team_number"));

		assertThatThrownBy(() -> service.createTeam(PROJECT, ORG, CLASS_E, "2팀", MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_NUMBER_DUPLICATED);
	}

	@Test
	@DisplayName("모르는 제약은 덮지 않는다 — 원래 예외를 그대로 올린다")
	void unknownConstraintIsNotDisguised() {
		DataIntegrityViolationException original = uniqueViolation("ck_team_max_member_count");
		when(teamRepository.saveAndFlush(any(Team.class))).thenThrow(original);

		assertThatThrownBy(() -> service.createTeam(PROJECT, ORG, CLASS_E, "2팀", MANAGER))
				.isSameAs(original);
	}

	@Test
	@DisplayName("이름 변경도 중복이면 409이고 이름은 그대로다")
	void renameToTakenNameIsRejected() {
		Team team = teamWithId("1팀");
		when(teamRepository.findByTeamIdAndOrgIdAndDeletedAtIsNull(TEAM_ID, ORG)).thenReturn(Optional.of(team));
		when(teamRepository.existsByProjectIdAndClassIdAndNameAndDeletedAtIsNull(PROJECT, CLASS_E, "2팀"))
				.thenReturn(true);

		assertThatThrownBy(() -> service.renameTeam(TEAM_ID, ORG, "2팀", MANAGER))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).errorCode())
				.isEqualTo(ProjectExecutionErrorCode.TEAM_NAME_DUPLICATED);

		assertThat(team.getName()).isEqualTo("1팀");
	}

	@Test
	@DisplayName("지금 이름 그대로 보내는 것은 중복이 아니다 — 편집 폼을 그대로 저장하는 흐름")
	void renameToItsOwnNameIsNotAConflict() {
		Team team = teamWithId("1팀");
		when(teamRepository.findByTeamIdAndOrgIdAndDeletedAtIsNull(TEAM_ID, ORG)).thenReturn(Optional.of(team));

		service.renameTeam(TEAM_ID, ORG, "1팀", MANAGER);

		assertThat(team.getName()).isEqualTo("1팀");
		verify(teamRepository, never()).existsByProjectIdAndClassIdAndNameAndDeletedAtIsNull(any(), any(), any());
		verify(teamRepository, never()).flush();
	}

	// ── fixtures ────────────────────────────────────────────────────────────

	/**
	 * Postgres가 UNIQUE 위반을 올릴 때의 모양. 제약 이름은 Hibernate가 뽑아 주기도 하고
	 * 메시지에만 남기도 해서, 서비스는 원인 사슬의 메시지까지 훑는다 — 여기서도 메시지에 싣는다.
	 */
	private DataIntegrityViolationException uniqueViolation(String constraintName) {
		return new DataIntegrityViolationException(
				"could not execute statement",
				new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraintName + "\""));
	}

	private Project project() {
		return Project.createMiniProject(ORG, COHORT, "미프 5차", 5,
				LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4), MANAGER);
	}

	private Team teamWithId(String name) {
		Team team = Team.builder()
				.projectId(PROJECT)
				.classId(CLASS_E)
				.orgId(ORG)
				.teamNumber("1")
				.name(name)
				.minMemberCount(1)
				.maxMemberCount(6)
				.createdBy(MANAGER)
				.build();
		ReflectionTestUtils.setField(team, "teamId", TEAM_ID);
		return team;
	}
}
