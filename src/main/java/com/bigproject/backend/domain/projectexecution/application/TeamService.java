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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * 빈 팀 하나를 만든다.
     *
     * <p>{@code min/maxMemberCount}는 임시로 1~6명을 고정값으로 둔다 — 팀 추가 모달에 이 값을
     * 입력받는 자리가 확인 안 됐다(정의 문서에 "빈 팀을 만든다"만 있음). 값을 어디서 받을지
     * (요청 파라미터 vs 프로젝트 기본값) 확인되면 그 값으로 바꾼다.
     *
     * @param classId 팀을 만들 반. 필수다.
     */
    @Transactional
    public Team createTeam(UUID projectId, UUID orgId, UUID classId, String name, UUID managerUserId) {
        UUID targetClassId = resolveTargetClassId(projectId, orgId, managerUserId, classId);

        /*
         * 🔴 번호는 **그 반 안에서** 센다. 종전에는 프로젝트 전체 팀 수 + 1이었는데,
         * 정의서·API 문서·시드는 모두 "반 안에서만 유일"을 전제한다(30차 R4의 `1팀`이
         * 여덟 번 나오던 목록이 그 증거다). 화면에서 만든 팀만 전역 번호를 받아 규칙이
         * 갈려 있었다.
         *
         * 🔴 <b>개수가 아니라 최대 번호 + 1이다</b>(46차 R1). 개수로 세면 1·2·3 중 2팀을
         * 해체한 반에서 다음 번호가 3이 되어 살아 있는 3팀과 {@code uq_team_..._team_number}가
         * 부딪힌다 — 해체 직후 팀을 다시 못 만드는 자리였다. 번호 컬럼이 text라 숫자로
         * 읽히는 것만 본다(자동 배분·수동 생성 모두 숫자를 넣는다).
         */
        int nextNumber = teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId).stream()
                .filter(team -> targetClassId.equals(team.getClassId()))
                .mapToInt(team -> parseTeamNumber(team.getTeamNumber()))
                .max()
                .orElse(0) + 1;

        assertTeamNameFree(projectId, targetClassId, name);

        Team team = Team.builder()
                .projectId(projectId)
                .classId(targetClassId)
                .orgId(orgId)
                .teamNumber(String.valueOf(nextNumber))
                .name(name)
                .minMemberCount(1)
                .maxMemberCount(6)
                .createdBy(managerUserId)
                .build();
        return saveTeam(team);
    }

    /**
     * 팀명 중복을 <b>DB에 닿기 전에</b> 도메인 코드로 끊는다.
     *
     * <p><b>이것만으로는 부족하다</b> — SELECT-then-INSERT라 동시 요청 두 건이 둘 다 통과할 수
     * 있다. 그래서 {@link #saveTeam}이 DB가 끊는 경우도 같은 코드로 옮긴다.
     *
     * <p>검사 범위({@code deleted_at IS NULL})는 DB 제약과 같다 — 활성 범위 부분 UNIQUE가
     * 2026-08-25 실 DB에 적용됐다(2026-08-25_team_unique_active_scope.sql). 적용 전에는 제약이
     * 이 검사보다 넓어 해체된 팀의 이름까지 잡았고, 그것이 47차 R1이었다.
     */
    private void assertTeamNameFree(UUID projectId, UUID classId, String name) {
        if (teamRepository.existsByProjectIdAndClassIdAndNameAndDeletedAtIsNull(projectId, classId, name)) {
            throw new ApiException(ProjectExecutionErrorCode.TEAM_NAME_DUPLICATED);
        }
    }

    /**
     * 🔴 {@code save}가 아니라 {@code saveAndFlush}다.
     *
     * <p>{@link Team}은 {@code GenerationType.UUID}라 {@code save()}만 쓰면 실제 INSERT(따라서
     * 제약 검사)가 <b>트랜잭션 커밋 시점까지 미뤄질 수 있다.</b> 그러면 예외가 이 catch를 지나쳐
     * 트랜잭션 밖에서 터지고, 잡는 코드가 없어 그대로 새 나간다 — 교안 등록(CurriculumServiceImpl)에서
     * 실측으로 확인된 것과 같은 함정이다.
     */
    private Team saveTeam(Team team) {
        try {
            return teamRepository.saveAndFlush(team);
        } catch (DataIntegrityViolationException exception) {
            throw translateTeamUniqueViolation(exception);
        }
    }

    /**
     * DB가 끊은 UNIQUE 위반을 화면이 읽을 수 있는 코드로 옮긴다.
     *
     * <p>종전에는 이 예외가 전역 처리기의 fallback({@code DATA_INTEGRITY_VIOLATION} —
     * "요청을 처리할 수 없습니다. 데이터 제약 조건에 맞지 않습니다.")으로 새 나갔다. 화면은
     * 무엇이 잘못됐는지 말할 수 없었고, 매니저는 <b>이름을 바꾸면 된다</b>는 것을 알 수 없었다.
     *
     * <p>이름과 번호를 나눠 옮기는 이유는 고칠 수 있는 주체가 다르기 때문이다 — 이름은 매니저가
     * 입력한 값이고, 번호는 서버가 매긴 값이라 다시 시도하면 다음 번호를 받는다.
     *
     * <p><b>모르는 제약은 감추지 않는다.</b> 우리가 아는 두 개가 아니면 원래 예외를 그대로
     * 올려 전역 처리기가 5xx/409로 다루게 둔다 — 엉뚱한 코드로 덮으면 원인을 못 찾는다.
     */
    private RuntimeException translateTeamUniqueViolation(DataIntegrityViolationException exception) {
        String detail = constraintDetail(exception);
        if (detail.contains("uq_team_project_id_class_id_name")) {
            return new ApiException(ProjectExecutionErrorCode.TEAM_NAME_DUPLICATED);
        }
        if (detail.contains("uq_team_project_id_class_id_team_number")) {
            return new ApiException(ProjectExecutionErrorCode.TEAM_NUMBER_DUPLICATED);
        }
        return exception;
    }

    /**
     * 어느 제약이 깨졌는지 찾는다. Hibernate가 제약 이름을 뽑아 주기도 하지만 드라이버·방언에
     * 따라 {@code null}이라, 원인 사슬의 메시지도 함께 훑는다 — 이름은 어느 쪽에든 들어 있다.
     */
    private String constraintDetail(Throwable exception) {
        StringBuilder detail = new StringBuilder();
        Throwable cause = exception;
        while (cause != null && detail.length() < 4000) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                detail.append(violation.getConstraintName()).append('\n');
            }
            detail.append(cause.getMessage()).append('\n');
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return detail.toString();
    }

    /** 번호 컬럼이 text다. 숫자로 읽히지 않는 값은 번호 경쟁에서 빼고 0으로 본다. */
    private int parseTeamNumber(String teamNumber) {
        try {
            return Integer.parseInt(teamNumber.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    /**
     * 요청이 지정한 반이 쓸 수 있는 반인지 검증한다. <b>반은 요청이 정한다 — 서버가 역산하지 않는다.</b>
     *
     * <p>담당 반인지, 이 프로젝트의 기수에 속한 반인지 둘 다 본다. 기수가 다르면 404이고 담당이
     * 아니면 403이다 — "없는 반"과 "남의 반"은 화면이 다른 말을 해야 한다.
     *
     * <p>종전에는 {@code classId}가 없으면 매니저의 담당 반을 역산했고, 반이 둘 이상이면
     * {@code MANAGER_CLASSROOM_AMBIGUOUS}로 막았다. 그 전제(매니저가 기수당 반 하나)가 애초에
     * 사실이 아니었고(이도윤 = 7기 B·D반), 역산이 성공하는 경우에도 대상 인원은 기수 전원이라
     * 다른 반 사람이 섞였다. 반을 요청이 정하게 되면서 역산 갈래와 그 에러 코드를 함께 지웠다.
     */
    private UUID resolveTargetClassId(UUID projectId, UUID orgId, UUID managerUserId, UUID requestedClassId) {
        Project project = projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));

        if (!belongsToCohort(requestedClassId, orgId, project.getCohortId())) {
            throw new ApiException(ProjectExecutionErrorCode.CLASS_NOT_FOUND);
        }
        if (!managedClassIds(orgId, managerUserId).contains(requestedClassId)) {
            throw new ApiException(ProjectExecutionErrorCode.CLASS_NOT_MANAGED);
        }
        return requestedClassId;
    }

    /**
     * 이 팀이 내가 맡은 반의 팀인가. 팀 목록이 이미 담당 반만 주므로 정상 흐름에서는 언제나 참이고,
     * 남의 반 teamId를 직접 부르는 요청만 여기서 끊긴다.
     */
    private void assertManagedTeam(Team team, UUID orgId, UUID managerUserId) {
        if (!managedClassIds(orgId, managerUserId).contains(team.getClassId())) {
            throw new ApiException(ProjectExecutionErrorCode.CLASS_NOT_MANAGED);
        }
    }

    private boolean belongsToCohort(UUID classId, UUID orgId, UUID cohortId) {
        return classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, orgId)
                .map(classroom -> classroom.getCohortId().equals(cohortId))
                .orElse(false);
    }

    public Team findTeam(UUID teamId, UUID orgId) {
        return teamRepository.findByTeamIdAndOrgIdAndDeletedAtIsNull(teamId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.TEAM_NOT_FOUND));
    }

    public List<Team> findTeams(UUID projectId, UUID orgId) {
        return teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId);
    }

    /**
     * 목록 화면(MG-08 팀 탭)이 쓰는 팀 목록이며 <b>담당 반으로 좁혀</b> 준다(30차 R3).
     *
     * <p>이 조회는 매니저 전용이라 오퍼레이터 갈래가 없다 — 언제나 좁힌다. 좁히지 않던 때는
     * 기수 전체 48팀이 나왔고, 팀 번호가 반마다 1부터 다시 시작해 목록에 `1팀`이 여덟 번
     * 나타났다. 같은 화면의 제출 현황 탭은 담당 반 6팀만 보여 주고 있었다.
     */
    public List<Team> findManagedTeams(UUID projectId, UUID orgId, UUID managerUserId, UUID classId) {
        List<UUID> scope = visibleClassIds(orgId, managerUserId, classId);
        if (scope.isEmpty()) {
            return List.of();
        }
        return teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId).stream()
                .filter(team -> scope.contains(team.getClassId()))
                .toList();
    }

    /**
     * 읽기 조회가 볼 반. 반을 지정했으면 그 하나로 좁히되 <b>담당 반일 때만</b>이다 —
     * 남의 반 ID를 넣어도 빈 결과일 뿐 에러는 아니다(읽기라 존재를 알릴 필요가 없다).
     */
    private List<UUID> visibleClassIds(UUID orgId, UUID managerUserId, UUID classId) {
        List<UUID> managed = managedClassIds(orgId, managerUserId);
        if (classId == null) {
            return managed;
        }
        return managed.contains(classId) ? List.of(classId) : List.of();
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

    /**
     * 팀 이름 변경. 중복이면 {@code TEAM_NAME_DUPLICATED}(409)다.
     *
     * <p>지금 이름 그대로 보내는 것은 중복이 아니다 — 화면이 편집 폼을 그대로 저장하는 흐름이
     * 있어서, 자기 이름에 자기가 걸리면 아무것도 안 바꾸는 요청이 409가 된다.
     *
     * <p>🔴 이름을 바꾼 뒤 {@code flush}한다. 더티 체킹 UPDATE는 커밋 시점에 나가므로,
     * flush하지 않으면 UNIQUE 위반이 이 메서드 밖에서 터져 도메인 코드로 옮길 자리가 없다
     * ({@link #saveTeam}과 같은 이유다).
     */
    @Transactional
    public void renameTeam(UUID teamId, UUID orgId, String newName, UUID managerUserId) {
        Team team = findTeam(teamId, orgId);
        assertManagedTeam(team, orgId, managerUserId);
        if (newName.equals(team.getName())) {
            return;
        }

        assertTeamNameFree(team.getProjectId(), team.getClassId(), newName);
        team.rename(newName);
        try {
            teamRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateTeamUniqueViolation(exception);
        }
    }

    /**
     * 이 프로젝트 참여자 중 지금 어느 팀에도 속하지 않은 사람. 정의 문서의
     * "팀에 들어가지 않은 사람이 n명 있어요" 배너가 이 목록의 크기를 쓴다.
     *
     * <p>🔴 <b>담당 반으로 좁힌다.</b> 종전에는 기수 전원이 나왔다 — 같은 응답의 {@code teams[]}는
     * 이미 담당 반만 주고 있었으므로(30차 R3) 한 화면 안에서 두 목록의 모집단이 달랐다.
     * 7기처럼 반이 열인 기수에서는 미배정 249명이 나와 배너가 늘 켜져 있었고, 그 사람들을
     * 팀에 넣어도 목록이 줄지 않았다. 이제 두 목록이 같은 모집단을 본다.
     */
    public List<ProjectMembershipQueryRepository.UnassignedMember> findUnassignedMembers(
            UUID projectId, UUID orgId, UUID managerUserId, UUID classId) {
        return projectMembershipQueryRepository.findUnassigned(
                projectId, orgId, visibleClassIds(orgId, managerUserId, classId));
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
        assertManagedTeam(team, orgId, actorUserId);

        if (submissionQueryRepository.hasAcceptedSubmission(teamId, orgId)) {
            throw new ApiException(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED);
        }

        // 🔴 팀의 반까지 함께 본다 — 프로젝트 소속만 보던 종전 검사로는 다른 반 사람이 들어왔다.
        if (!projectMembershipQueryRepository.belongsToProjectAndClass(
                projectMembershipId, projectId, orgId, team.getClassId())) {
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
    public void removeMember(UUID teamId, UUID orgId, UUID projectId, UUID traineeId, UUID managerUserId) {
        Team team = findTeam(teamId, orgId);
        assertManagedTeam(team, orgId, managerUserId);

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

    /**
     * 팀 해체(32차 R14①).
     *
     * <p>화면에 있는데 부를 자리가 없던 셋 중 하나다. 도메인 동작은 이미 있었고 엔드포인트만
     * 없었다 — 그래서 <b>인원 0명짜리 팀이 남아도 지울 방법이 없었고</b>, 그 팀이 제출 현황에서
     * 「미제출 ⚠」로 잡혀 조치가 필요한 것처럼 보였다.
     *
     * <h2>팀원은 미배정으로 되돌린다</h2>
     *
     * <p>팀 행만 지우면 그 사람들이 <b>해체된 팀에 속한 채</b> 남아 어디에도 안 보인다.
     * 배정 종료 시각을 찍어 미배정으로 돌려놓으면 자동 배분·수동 배정의 대상이 된다 —
     * 프론트가 요청한 흐름이 그것이다.
     *
     * <p>지우지 않고 종료 시각만 찍는 것은 {@link #removeMember}와 같다. 과거에 이 팀
     * 소속이었다는 사실이 남아야 그 회차의 제출·결과 귀속이 유지된다.
     *
     * <h2>제출한 팀은 막는다</h2>
     *
     * <p>배정·제외와 같은 이유다. 정상 접수된 제출이 있는 팀을 해체하면 그 제출이 팀 없이
     * 뜬다. {@code TEAM_SUBMISSION_LOCKED}로 끊고 화면이 이유를 말하게 한다.
     */
    @Transactional
    public void disbandTeam(UUID teamId, UUID orgId, UUID managerUserId) {
        Team team = findTeam(teamId, orgId);
        assertManagedTeam(team, orgId, managerUserId);

        if (submissionQueryRepository.hasAcceptedSubmission(teamId, orgId)) {
            throw new ApiException(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED);
        }

        OffsetDateTime now = OffsetDateTime.now();
        teamMembershipRepository.findByTeamIdAndOrgIdAndToAtIsNull(teamId, orgId)
                .forEach(membership -> membership.unassign(now));

        team.disband();
    }

    /**
     * 반 통째로 해체(46차 R3).
     *
     * <p>자동 배분이 <b>한 번에 여러 팀을 만드는</b> 액션이라 되돌리는 쪽도 한 번이어야 한다.
     * 종전에는 반 하나를 다시 짜려면 팀 수만큼 {@code DELETE}를 순서대로 눌러야 했고, 그 중
     * 하나가 실패하면 반이 반쯤 해체된 채로 남았다.
     *
     * <h2>DRAFT만 해체한다</h2>
     *
     * <p>확정된 팀이 하나라도 섞여 있으면 {@code TEAM_CONFIRMED_LOCKED}로 끊는다 —
     * 이 API의 용도는 <b>확정 전 되돌리기</b>이지 확정 취소가 아니다. 확정을 되돌리려면
     * [편성 다시 열기]({@link #reopenTeams})가 이미 있고, 그쪽이 되돌리기의 정식 경로다.
     * DDL도 한 반의 활성 팀은 전부 DRAFT이거나 전부 CONFIRMED여야 한다고 못 박으므로,
     * 정상 상태에서 이 갈래는 "그 반은 이미 확정됐다"와 같은 뜻이다.
     *
     * <p>팀 하나짜리 {@link #disbandTeam}은 그대로 CONFIRMED도 해체한다 — 일괄 쪽만 좁히는
     * 것이고 할 수 있던 일이 없어지지는 않는다.
     *
     * <h2>전부 아니면 아무것도 아니다</h2>
     *
     * <p>제출 확인을 <b>해체를 시작하기 전에</b> 한 번에 끝낸다. 트랜잭션이 있으니 중간에
     * 던져도 롤백되지만, 일괄 작업에서 "몇 개까지 지워졌나"를 롤백에 맡기고 싶지 않다.
     * 걸린 팀 이름을 메시지에 실어 보낸다 — 팀이 여럿인 요청이 409로 끊길 때 화면이
     * 어느 팀 때문인지 말할 수 있어야 한다.
     *
     * @param classId 비울 반. 필수다.
     * @return 해체한 팀 수와 미배정으로 돌아간 인원 수. 이미 빈 반이면 둘 다 0이다(에러가 아니다).
     */
    @Transactional
    public DisbandResult disbandClassTeams(UUID projectId, UUID orgId, UUID classId, UUID managerUserId) {
        UUID targetClassId = resolveTargetClassId(projectId, orgId, managerUserId, classId);

        List<Team> teams = teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId).stream()
                .filter(team -> targetClassId.equals(team.getClassId()))
                .toList();
        // 이미 비어 있는 반은 목표 상태다. 다시 눌러도 같은 답이 나와야 한다.
        if (teams.isEmpty()) {
            return new DisbandResult(0, 0);
        }

        if (teams.stream().anyMatch(Team::isConfirmed)) {
            throw new ApiException(ProjectExecutionErrorCode.TEAM_CONFIRMED_LOCKED);
        }

        Set<UUID> lockedTeamIds = submissionQueryRepository.findTeamIdsWithAcceptedSubmission(
                teams.stream().map(Team::getTeamId).toList(), orgId);
        if (!lockedTeamIds.isEmpty()) {
            String lockedNames = teams.stream()
                    .filter(team -> lockedTeamIds.contains(team.getTeamId()))
                    .map(Team::getName)
                    .collect(Collectors.joining(", "));
            throw new ApiException(ProjectExecutionErrorCode.TEAM_SUBMISSION_LOCKED,
                    "이미 제출한 팀이 있어 반을 비울 수 없습니다: " + lockedNames);
        }

        OffsetDateTime now = OffsetDateTime.now();
        int unassignedMemberCount = 0;
        for (Team team : teams) {
            List<TeamMembership> memberships =
                    teamMembershipRepository.findByTeamIdAndOrgIdAndToAtIsNull(team.getTeamId(), orgId);
            memberships.forEach(membership -> membership.unassign(now));
            unassignedMemberCount += memberships.size();
            team.disband();
        }
        return new DisbandResult(teams.size(), unassignedMemberCount);
    }

    /**
     * 반 통째로 해체한 결과. 화면이 "n개 팀을 해체했고 m명이 미배정으로 돌아갔습니다"를
     * 말할 수 있도록 두 수를 함께 낸다 — 목록을 다시 받아 세는 것으로는 "몇 명이 돌아왔나"를
     * 알 수 없다(해체 전 인원을 모르기 때문이다).
     */
    public record DisbandResult(int disbandedTeamCount, int unassignedMemberCount) {
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
     * <h2>🔴 반 단위로 돈다</h2>
     *
     * <p>대상 인원도, "팀이 하나도 없다"는 선행 조건도 <b>그 반</b> 기준이다. 종전에는 둘 다
     * 프로젝트 전역이라 (1) 기수 전원을 한 반의 팀으로 몰아넣었고 (2) 다른 반 매니저가 먼저
     * 팀을 만들면 내 반은 영영 자동 배분을 쓸 수 없었다.
     *
     * @param classId       배분할 반. 필수다.
     * @param teamSize      팀 하나의 목표 인원(예: 3). 마지막 팀만 나머지를 더 받는다.
     * @param skillBalanced true면 실력 섞기, false면 무작위
     */
    @Transactional
    public List<Team> autoAssign(UUID projectId, UUID orgId, UUID classId,
            int teamSize, boolean skillBalanced, UUID managerUserId) {
        UUID targetClassId = resolveTargetClassId(projectId, orgId, managerUserId, classId);

        /*
         * 🔴 해체된 팀은 "이미 짜인 팀"이 아니다(46차 R1). 종전에는 이 판정이 해체된 팀까지
         * 세어, 팀을 전부 해체한 반이 자동 배분을 **영영** 못 쓰고 AUTO_ASSIGN_NOT_ALLOWED로만
         * 막혔다 — 프론트는 이 409를 "쓰기 판정은 정확하다"는 근거로 읽었지만, 실제로는 목록이
         * 유령 팀을 보여 준 것과 같은 원인이었다.
         */
        boolean classAlreadyHasTeams = teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId).stream()
                .anyMatch(team -> targetClassId.equals(team.getClassId()));
        if (classAlreadyHasTeams) {
            throw new ApiException(ProjectExecutionErrorCode.AUTO_ASSIGN_NOT_ALLOWED);
        }

        List<ProjectMembershipQueryRepository.UnassignedMember> members =
                projectMembershipQueryRepository.findUnassigned(projectId, orgId, List.of(targetClassId));
        if (members.isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.NO_MEMBERS_TO_ASSIGN);
        }

        List<ProjectMembershipQueryRepository.UnassignedMember> ordered = skillBalanced
                ? orderBySkill(projectId, orgId, members)
                : shuffle(members);

        int teamCount = Math.max(1, (int) Math.ceil(ordered.size() / (double) teamSize));
        List<Team> teams = createEmptyTeams(projectId, orgId, targetClassId, teamCount, managerUserId);

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

    /** 반이 이미 정해진 뒤에 불린다 — 번호도 이름도 그 반 안에서 1부터 매긴다. */
    private List<Team> createEmptyTeams(UUID projectId, UUID orgId, UUID classId,
            int teamCount, UUID managerUserId) {
        List<Team> teams = new ArrayList<>();
        for (int i = 1; i <= teamCount; i++) {
            // 살아 있는 팀이 없는 반에서만 불리므로 정상 경로에서는 부딪힐 이름이 없다. 그래도
            // saveTeam으로 넣는다 — 동시 요청이 겹치면 DB가 끊는데, 그때 fallback이 아니라
            // 무엇이 겹쳤는지 말하는 코드가 나가야 한다.
            // (활성 범위 부분 UNIQUE 적용 전에는 해체된 팀이 이름을 붙잡아 여기서 끊겼다 — 47차 R2.)
            teams.add(saveTeam(Team.builder()
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
     *
     * <p>🔴 <b>반 단위다.</b> 확정 대상도 미배정 판정도 그 반만 본다. 종전에는 둘 다 프로젝트
     * 전역이라 <b>다른 반에 미배정이 남아 있으면 내 반 확정이 막혔다</b> — 반마다 편성 진도가
     * 다른 것이 정상인데 가장 늦은 반이 나머지를 전부 붙잡고 있었다.
     *
     * @param classId 확정할 반. 필수다.
     */
    @Transactional
    public void confirmTeams(UUID projectId, UUID orgId, UUID classId, UUID managerUserId) {
        List<UUID> scope = confirmScope(projectId, orgId, classId, managerUserId);

        List<Team> teams = teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId).stream()
                .filter(team -> scope.contains(team.getClassId()))
                .toList();
        if (teams.isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.NO_TEAMS_TO_CONFIRM);
        }
        if (!projectMembershipQueryRepository.findUnassigned(projectId, orgId, scope).isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.TEAMS_NOT_READY);
        }
        teams.forEach(Team::confirm);
    }

    /** 확정·다시 열기가 건드릴 반. 요청이 정한 하나다(검증 후). */
    private List<UUID> confirmScope(UUID projectId, UUID orgId, UUID classId, UUID managerUserId) {
        return List.of(resolveTargetClassId(projectId, orgId, managerUserId, classId));
    }

    /**
     * 편성 다시 열기(정의 문서 ④, "아직 되돌릴 수 있다"). 제출 시작 후엔 막아야 하는데,
     * Submission 도메인의 실제 판정(hasAcceptedSubmission)이 assignMember/removeMember에는
     * 있지만, 여기(팀 전체 되돌리기)에는 아직 없다 — "확정 되돌리기"와 "개별 배정 변경"의
     * 막는 기준이 같아야 하는지 아직 정의 문서로 확인되지 않아 우선 배정/제외만 막아 둔다.
     */
    @Transactional
    public void reopenTeams(UUID projectId, UUID orgId, UUID classId, UUID managerUserId) {
        List<UUID> scope = confirmScope(projectId, orgId, classId, managerUserId);
        teamRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId).stream()
                .filter(team -> scope.contains(team.getClassId()))
                .forEach(Team::reopen);
    }
}