package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import com.bigproject.backend.domain.academicoperations.domain.ManagerAssignment;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.projectexecution.domain.AssignmentMethod;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MG-08 팀 편성. {@link com.bigproject.backend.domain.projectexecution.domain.TeamStatus}에는
 * DRAFT/CONFIRMED 둘뿐이라(DB CHECK 확인 완료), 화면이 쓰는 "편성 중 → 확정됨 → 제출 시작됨" 단계는
 * 이 서비스가 status + 미배정 인원 유무 + 제출 존재 유무를 조합해 판정한다.
 *
 * <p>Submission 도메인은 아직 이 저장소에 엔티티가 없지만(박종호 담당, 미착수) submission
 * 테이블 자체는 실제 DB에 존재해 raw SQL(SubmissionQueryRepository)로 "제출된 팀은 구성을
 * 못 바꾼다"는 안전장치까지는 채웠다. 다만 "제출 후 팀 이동(TRANSFER)"은 범위 밖으로 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final ProjectMembershipQueryRepository projectMembershipQueryRepository;
    private final ProjectRepository projectRepository;
    private final ClassroomRepository classroomRepository;
    private final ManagerAssignmentRepository managerAssignmentRepository;
    private final AssessmentAxisQueryRepository assessmentAxisQueryRepository;
    private final SubmissionQueryRepository submissionQueryRepository;

    /**
     * 빈 팀 하나를 만든다. {@code classId}는 요청에 없다 — Project 엔티티엔 반 연관이 없어서,
     * 팀을 만드는 매니저가 이 프로젝트의 기수(cohort)에서 담당하는 반을 서버가 역산한다.
     * 매니저가 한 기수에 반을 하나만 담당한다는 전제다(정의 문서 URL·화면 어디에도 classId
     * 파라미터가 없음) — 반이 여러 개면 400으로 막고, 필요해지면 파라미터로 받게 바꾼다.
     *
     * <p>{@code min/maxMemberCount}는 임시로 1~6명을 고정값으로 둔다 — 팀 추가 모달에 이 값을
     * 입력받는 자리가 확인 안 됐다(정의 문서에 "빈 팀을 만든다"만 있음). 값을 어디서 받을지
     * (요청 파라미터 vs 프로젝트 기본값) 확인되면 그 값으로 바꾼다.
     */
    @Transactional
    public Team createTeam(UUID projectId, UUID orgId, String name, UUID managerUserId) {
        Project project = projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));

        UUID classId = resolveManagerClassId(project.getCohortId(), orgId, managerUserId);

        int nextNumber = teamRepository.findByProjectIdAndOrgId(projectId, orgId).size() + 1;

        Team team = Team.builder()
                .projectId(projectId)
                .classId(classId)
                .orgId(orgId)
                .teamNumber(String.valueOf(nextNumber))
                .name(name)
                .minMemberCount(1)
                .maxMemberCount(6)
                .createdBy(managerUserId)
                .build();
        return teamRepository.save(team);
    }

    /** 로그인한 매니저가 이 기수에서 담당하는 반을 하나로 정한다. 0개·2개 이상이면 400. */
    private UUID resolveManagerClassId(UUID cohortId, UUID orgId, UUID managerUserId) {
        List<UUID> classIdsInCohort = managerAssignmentRepository
                .findByManagerUserIdAndOrgIdAndUnassignedAtIsNull(managerUserId, orgId).stream()
                .map(ManagerAssignment::getClassId)
                .distinct()
                .filter(classId -> classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, orgId)
                        .map(classroom -> classroom.getCohortId().equals(cohortId))
                        .orElse(false))
                .toList();

        if (classIdsInCohort.size() != 1) {
            throw new ApiException(ProjectExecutionErrorCode.MANAGER_CLASSROOM_AMBIGUOUS);
        }
        return classIdsInCohort.get(0);
    }

    public Team findTeam(UUID teamId, UUID orgId) {
        return teamRepository.findByTeamIdAndOrgId(teamId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.TEAM_NOT_FOUND));
    }

    public List<Team> findTeams(UUID projectId, UUID orgId) {
        return teamRepository.findByProjectIdAndOrgId(projectId, orgId);
    }

    /**
     * 목록 화면(MG-08 팀 탭)이 쓰는 팀 목록이며 <b>담당 반으로 좁혀</b> 준다(30차 R3).
     *
     * <p>이 조회는 매니저 전용이라 오퍼레이터 갈래가 없다 — 언제나 좁힌다. 좁히지 않던 때는
     * 기수 전체 48팀이 나왔고, 팀 번호가 반마다 1부터 다시 시작해 목록에 `1팀`이 여덟 번
     * 나타났다. 같은 화면의 제출 현황 탭은 담당 반 6팀만 보여 주고 있었다.
     */
    public List<Team> findManagedTeams(UUID projectId, UUID orgId, UUID managerUserId) {
        List<UUID> managedClassIds = managedClassIds(orgId, managerUserId);
        if (managedClassIds.isEmpty()) {
            return List.of();
        }
        return teamRepository.findByProjectIdAndOrgId(projectId, orgId).stream()
                .filter(team -> managedClassIds.contains(team.getClassId()))
                .toList();
    }

    /** 지금 담당 중인 반. 네이티브 SQL 쪽 판정과 같게 {@code status}와 해제 시각을 함께 본다. */
    private List<UUID> managedClassIds(UUID orgId, UUID managerUserId) {
        return managerAssignmentRepository
                .findByManagerUserIdAndOrgIdAndStatusAndUnassignedAtIsNull(managerUserId, orgId, "ACTIVE")
                .stream()
                .map(ManagerAssignment::getClassId)
                .distinct()
                .toList();
    }

    /**
     * 여러 팀의 현재 구성원을 팀 ID별로 <b>한 번에</b> 읽는다(30차 R4).
     *
     * <p>종전 {@code countMembersByTeamIds}는 주석이 "한 번에 가져온다"였는데 구현은 팀마다
     * count 질의를 돌리는 루프였다 — 48팀이면 48번이다. 인원 수는 이 목록의 길이로 세므로
     * 세기 전용 질의가 필요 없다.
     */
    public Map<UUID, List<ProjectMembershipQueryRepository.TeamMember>> findMembersByTeamIds(List<UUID> teamIds) {
        return projectMembershipQueryRepository.findMembersByTeamIds(teamIds).stream()
                .collect(Collectors.groupingBy(ProjectMembershipQueryRepository.TeamMember::teamId));
    }

    /** 팀이 속한 반 이름. 팀 번호가 반마다 1부터 다시 시작해 이름 없이는 팀을 구분할 수 없다(30차 R4). */
    public Map<UUID, String> findClassNames(List<UUID> classIds, UUID orgId) {
        if (classIds.isEmpty()) {
            return Map.of();
        }
        return classroomRepository.findByClassIdInAndOrgIdAndDeletedAtIsNull(classIds, orgId).stream()
                .collect(Collectors.toMap(Classroom::getClassId, Classroom::getName));
    }

    @Transactional
    public void renameTeam(UUID teamId, UUID orgId, String newName) {
        findTeam(teamId, orgId).rename(newName);
    }

    /**
     * 이 프로젝트 참여자 중 지금 어느 팀에도 속하지 않은 사람. 정의 문서의
     * "팀에 들어가지 않은 사람이 n명 있어요" 배너가 이 목록의 크기를 쓴다.
     */
    public List<ProjectMembershipQueryRepository.UnassignedMember> findUnassignedMembers(UUID projectId, UUID orgId) {
        return projectMembershipQueryRepository.findUnassigned(projectId, orgId);
    }

    /**
     * 특정 사람을 이 팀으로 배정. 이미 다른 팀에 있으면 그 배정부터 닫고 새로 연다.
     * projectMembershipId가 이 프로젝트 소속인지는 여기서 검증한다 — 다른 프로젝트·다른 반 사람을
     * 실수로 팀에 넣는 것을 DB 제약이 아니라 서비스가 막는다.
     *
     * <p>이미 정상 접수(ACCEPTED)된 제출이 있는 팀은 구성을 못 바꾼다 — 제출된 코드가
     * 그 시점의 팀 구성에 묶여 있어(정의 문서), 나중에 사람이 바뀌면 "누가 냈는지"가 흐트러진다.
     */
    @Transactional
    public TeamMembership assignMember(UUID teamId, UUID orgId, UUID projectId,
                                       UUID projectMembershipId, AssignmentMethod method, UUID actorUserId) {
        Team team = findTeam(teamId, orgId);

        if (submissionQueryRepository.hasAcceptedSubmission(teamId, orgId)) {
            throw new ApiException(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED);
        }

        if (!projectMembershipQueryRepository.belongsToProject(projectMembershipId, projectId, orgId)) {
            throw new ApiException(ProjectExecutionErrorCode.PROJECT_MEMBERSHIP_NOT_FOUND);
        }

        OffsetDateTime now = OffsetDateTime.now();
        teamMembershipRepository.findEffectiveAt(projectMembershipId, now)
                .ifPresent(existing -> existing.unassign(now));

        // 기존 배정을 닫는 UPDATE를 새 배정 INSERT보다 먼저 내보낸다.
        // Hibernate는 flush 시 INSERT를 UPDATE보다 먼저 실행하므로, 이 줄이 없으면
        // 유니크 인덱스가 붙는 순간 배정이 실패한다(ClassroomService와 동일 이슈).
        teamMembershipRepository.flush();

        TeamMembership membership = TeamMembership.builder()
                .teamId(team.getTeamId())
                .projectMembershipId(projectMembershipId)
                .orgId(orgId)
                .assignmentMethod(method)
                .fromAt(now)
                .assignedBy(actorUserId)
                .build();

        return teamMembershipRepository.save(membership);
    }

    /**
     * 팀원 제외. 화면은 traineeId(사용자 ID)로 부르므로, projectMembershipId로 변환해
     * 현재 유효한 배정을 찾아 종료 시각만 찍는다(지우지 않는다).
     *
     * <p>배정과 같은 이유로, 이미 정상 접수된 제출이 있는 팀은 제외도 막는다.
     */
    @Transactional
    public void removeMember(UUID teamId, UUID orgId, UUID projectId, UUID traineeId) {
        Team team = findTeam(teamId, orgId);

        if (submissionQueryRepository.hasAcceptedSubmission(teamId, orgId)) {
            throw new ApiException(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED);
        }

        UUID projectMembershipId = projectMembershipQueryRepository
                .findProjectMembershipId(projectId, orgId, traineeId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_MEMBERSHIP_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now();
        TeamMembership membership = teamMembershipRepository
                .findEffectiveAt(projectMembershipId, now)
                .filter(m -> m.getTeamId().equals(team.getTeamId()))
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.TEAM_MEMBERSHIP_NOT_FOUND));

        membership.unassign(now);
    }

    @Transactional
    public void disbandTeam(UUID teamId, UUID orgId) {
        findTeam(teamId, orgId).disband();
    }

    /**
     * 자동 배분(정의 문서 "자동 배분이 하는 일" 4단계).
     *
     * <p>팀이 하나도 없을 때만 된다 — 이미 짜인 팀을 자동 배분이 뒤엎지 않는다(정의 문서).
     * 실력 섞기는 <b>직전 회차(같은 카테고리 바로 앞 순번)의 도달 단계</b>를 봐서 뱀 순서로 나누는데,
     * 직전 회차가 없거나(1차 프로젝트) 그 회차 데이터가 하나도 없으면 무작위로 조용히 폴백한다 —
     * "실력 섞기를 실행했는데 아무 근거가 없어서 실패"가 아니라 "근거가 없으면 무작위와 같다"로
     * 다루는 것이 정의 문서의 "1차라면 무작위만 쓸 수 있습니다"와 맞는다.
     *
     * @param teamSize      팀 하나의 목표 인원(예: 3). 마지막 팀만 나머지를 더 받는다.
     * @param skillBalanced true면 실력 섞기, false면 무작위
     */
    @Transactional
    public List<Team> autoAssign(UUID projectId, UUID orgId, int teamSize, boolean skillBalanced, UUID managerUserId) {
        if (!teamRepository.findByProjectIdAndOrgId(projectId, orgId).isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.AUTO_ASSIGN_NOT_ALLOWED);
        }

        List<ProjectMembershipQueryRepository.UnassignedMember> members =
                projectMembershipQueryRepository.findUnassigned(projectId, orgId);
        if (members.isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.NO_MEMBERS_TO_ASSIGN);
        }

        List<ProjectMembershipQueryRepository.UnassignedMember> ordered = skillBalanced
                ? orderBySkill(projectId, orgId, members)
                : shuffle(members);

        int teamCount = Math.max(1, (int) Math.ceil(ordered.size() / (double) teamSize));
        List<Team> teams = createEmptyTeams(projectId, orgId, teamCount, managerUserId);

        // 뱀 순서(1·2·3...3·2·1)로 배분 — 정렬 순서상 앞쪽(=강한 쪽)이 팀마다 고르게 퍼진다.
        OffsetDateTime now = OffsetDateTime.now();
        int direction = 1;
        int teamIndex = 0;
        for (ProjectMembershipQueryRepository.UnassignedMember member : ordered) {
            Team team = teams.get(teamIndex);
            teamMembershipRepository.save(TeamMembership.builder()
                    .teamId(team.getTeamId())
                    .projectMembershipId(member.projectMembershipId())
                    .orgId(orgId)
                    .assignmentMethod(AssignmentMethod.AUTO)
                    .fromAt(now)
                    .assignedBy(managerUserId)
                    .build());

            teamIndex += direction;
            if (teamIndex == teams.size()) {
                teamIndex = teams.size() - 1;
                direction = -1;
            } else if (teamIndex < 0) {
                teamIndex = 0;
                direction = 1;
            }
        }

        return teams;
    }

    private List<ProjectMembershipQueryRepository.UnassignedMember> shuffle(
            List<ProjectMembershipQueryRepository.UnassignedMember> members) {
        List<ProjectMembershipQueryRepository.UnassignedMember> copy = new ArrayList<>(members);
        Collections.shuffle(copy);
        return copy;
    }

    /** 직전 회차(같은 카테고리 바로 앞 순번)의 도달 단계 내림차순. 근거가 없으면 무작위로 폴백. */
    private List<ProjectMembershipQueryRepository.UnassignedMember> orderBySkill(
            UUID projectId, UUID orgId, List<ProjectMembershipQueryRepository.UnassignedMember> members) {

        Project project = projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));

        List<Project> sameCategory = projectRepository
                .findByCohortIdAndOrgIdAndProjectCategoryAndDeletedAtIsNullOrderBySequenceNoAsc(
                        project.getCohortId(), orgId, project.getProjectCategory());

        Project previousRound = null;
        for (Project candidate : sameCategory) {
            if (candidate.getSequenceNo() < project.getSequenceNo()
                    && (previousRound == null || candidate.getSequenceNo() > previousRound.getSequenceNo())) {
                previousRound = candidate;
            }
        }

        if (previousRound == null) {
            return shuffle(members); // 1차 프로젝트 — 비교 대상 없음
        }

        Map<UUID, Integer> reachedAxis = assessmentAxisQueryRepository
                .findReachedAxisByProject(previousRound.getProjectId(), orgId);

        if (reachedAxis.isEmpty()) {
            return shuffle(members); // 직전 회차는 있는데 응시 기록이 없음(아직 아무도 안 봄)
        }

        List<ProjectMembershipQueryRepository.UnassignedMember> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(
                        (ProjectMembershipQueryRepository.UnassignedMember m) -> reachedAxis.getOrDefault(m.userId(), 0))
                .reversed());
        return ordered;
    }

    private List<Team> createEmptyTeams(UUID projectId, UUID orgId, int teamCount, UUID managerUserId) {
        UUID classId = resolveManagerClassId(
                projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                        .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND))
                        .getCohortId(),
                orgId, managerUserId);

        List<Team> teams = new ArrayList<>();
        for (int i = 1; i <= teamCount; i++) {
            teams.add(teamRepository.save(Team.builder()
                    .projectId(projectId)
                    .classId(classId)
                    .orgId(orgId)
                    .teamNumber(String.valueOf(i))
                    .name(i + "팀")
                    .minMemberCount(1)
                    .maxMemberCount(6)
                    .createdBy(managerUserId)
                    .build()));
        }
        return teams;
    }

    /**
     * 팀 편성 확정(정의 문서 ③→④). 전원 배정이 안 됐으면 확정할 수 없다 —
     * "미배정이 있으면 제출이 열리지 않는다"는 화면 규칙을 서버도 같이 지킨다.
     */
    @Transactional
    public void confirmTeams(UUID projectId, UUID orgId) {
        List<Team> teams = teamRepository.findByProjectIdAndOrgId(projectId, orgId);
        if (teams.isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.NO_TEAMS_TO_CONFIRM);
        }
        if (!projectMembershipQueryRepository.findUnassigned(projectId, orgId).isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.TEAMS_NOT_READY);
        }
        teams.forEach(Team::confirm);
    }

    /**
     * 편성 다시 열기(정의 문서 ④, "아직 되돌릴 수 있다"). 제출 시작 후엔 막아야 하는데,
     * Submission 도메인의 실제 판정(hasAcceptedSubmission)이 assignMember/removeMember에는
     * 있지만, 여기(팀 전체 되돌리기)에는 아직 없다 — "확정 되돌리기"와 "개별 배정 변경"의
     * 막는 기준이 같아야 하는지 아직 정의 문서로 확인되지 않아 우선 배정/제외만 막아 둔다.
     */
    @Transactional
    public void reopenTeams(UUID projectId, UUID orgId) {
        teamRepository.findByProjectIdAndOrgId(projectId, orgId).forEach(Team::reopen);
    }
}