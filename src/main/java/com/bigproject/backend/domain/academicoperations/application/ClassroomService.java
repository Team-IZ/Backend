package com.bigproject.backend.domain.academicoperations.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.academicoperations.domain.ClassMembership;
import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import com.bigproject.backend.domain.academicoperations.domain.ClassroomDependencyRepository;
import com.bigproject.backend.domain.academicoperations.domain.ManagerAssignment;
import com.bigproject.backend.domain.academicoperations.domain.ManagerDirectoryRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassMembershipRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.academicoperations.domain.CohortMember;
import com.bigproject.backend.domain.academicoperations.infrastructure.CohortMemberRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.CohortRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 반 관련 업무 규칙을 처리하는 서비스
// 조회는 기본이 읽기 전용이고, 데이터를 바꾸는 메서드에만 따로 @Transactional을 붙임
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassroomService {

    // 해당 매니저 배정의 업무 상태가 코드 캐내로그가 아닌 enum·코드 테이블 검증으로 옮겨도 되도록 상수화
    private static final String ASSIGNMENT_STATUS_ACTIVE = "ACTIVE";

    private final ClassroomRepository classroomRepository;
    private final CohortMemberRepository cohortMemberRepository;
    private final ClassMembershipRepository classMembershipRepository;
    private final ManagerAssignmentRepository managerAssignmentRepository;
    private final ManagerDirectoryRepository managerDirectoryRepository;
    private final ClassroomDependencyRepository classroomDependencyRepository;
    private final CohortRepository cohortRepository;

    // 반 하나에 대한 응답 조립용 뷰: 담당 매니저(회원 ID·이름·이메일)와 활성 교육생 수를 함께 담음
    // 이름·이메일은 ManagerDirectoryRepository(읽기 전용 조회 포트)로 채운다. 반 카드가 `이도윤 · lee@…`를
    // 그리는데 ID만 주면 화면을 만들 수 없고, 이름만 주면 동명이인을 가릴 수 없다(9차 R5).
    public record ClassroomView(Classroom classroom,
                                List<ManagerDirectoryRepository.ManagerProfile> managers,
                                long traineeCount) {
    }

    // 반 생성 + 담당 매니저 배정을 한 트랜잭션으로 처리한다.
    // 별도 호출로 나누면 중간에 실패했을 때 "반만 있고 매니저는 없는" 반쪽짜리 상태가 방치될 수 있다.
    @Transactional
    public ClassroomView createClassroom(UUID orgId, UUID cohortId, String name, Integer capacity,
                                         List<UUID> managerUserIds, UUID creatorUserId) {
        // 같은 기수 안에서 반 이름이 겹치면 안 되는데 DB에 제약이 없어서 여기서 확인
        // (동시 요청 시 이 검사를 통과할 수도 있다. 부분 유니크 인덱스를 추가하면 DB가 막아준다.)
        if (classroomRepository.existsByCohortIdAndNameAndDeletedAtIsNull(cohortId, name)) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NAME_TAKEN, "이미 존재하는 반 이름입니다: " + name);
        }

        Classroom classroom = Classroom.builder()
                .orgId(orgId)
                .cohortId(cohortId)
                .name(name)
                .capacity(capacity)
                .createdBy(creatorUserId)
                .build();

        // saveAndFlush로 class 행을 먼저 INSERT한다.
        // manager_assignment.class_id가 class를 참조하는 FK인데, 엔티티는 UUID로만 연결되어 있어서
        // Hibernate가 이 순서 의존성을 모른다. application.yaml의 order_inserts=true가 INSERT 순서를
        // 엔티티 종류별로 재배치하므로, 명시적으로 flush하지 않으면 FK 위반이 날 수 있다.
        classroomRepository.saveAndFlush(classroom);

        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> assignedManagerIds = replaceClassroomManagers(
                classroom.getClassId(), orgId, managerUserIds, creatorUserId, now);

        // 방금 만든 반이라 교육생이 있을 수 없으므로 조회 없이 바로 0으로 조립
        return new ClassroomView(classroom, resolveManagers(orgId, assignedManagerIds), 0);
    }

    public Classroom findClassroom(UUID classId, UUID orgId) {
        return classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, orgId)
                .orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND));
    }

    public ClassroomView findClassroomView(UUID classId, UUID orgId) {
        return toView(findClassroom(classId, orgId), orgId);
    }

    /**
     * 반 목록. {@code scopedManagerId}를 주면 그 매니저가 <b>현재 담당하는 반만</b> 돌려준다.
     *
     * <p>화면의 `반 · 전체` 드롭다운을 채우는 값이라 <b>명단과 같은 모집단</b>이어야 한다 —
     * 매니저 명단은 담당 반으로 좁혀져 있는데(교육생 명단 조회의 매니저 스코프) 드롭다운만
     * 기수 전체 10개를 보여주면, 고를 수는 있는데 고르면 늘 비는 반이 생긴다.
     *
     * @param scopedManagerId 담당 반으로 좁힐 매니저. 오퍼레이터는 null이며 기수 전체를 본다
     */
    public List<ClassroomView> findClassroomViews(UUID cohortId, UUID orgId, UUID scopedManagerId) {
        // 22차 R7 — 없는 기수도 200 · 반 0으로 답하고 있었다. 스펙에는 404를 적어 두고 실제로는
        // 검사하지 않아, 「반을 아직 안 만든 기수」와 「그런 기수가 없다」가 구분되지 않았다.
        if (!cohortRepository.existsByCohortIdAndOrgIdAndDeletedAtIsNull(cohortId, orgId)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
        }
        List<Classroom> classrooms = classroomRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByNameAsc(cohortId, orgId);
        if (scopedManagerId != null) {
            Set<UUID> assignedClassIds = managerAssignmentRepository
                    .findByManagerUserIdAndOrgIdAndUnassignedAtIsNull(scopedManagerId, orgId).stream()
                    .filter(assignment -> ASSIGNMENT_STATUS_ACTIVE.equals(assignment.getStatus()))
                    .map(ManagerAssignment::getClassId)
                    .collect(Collectors.toSet());
            classrooms = classrooms.stream()
                    .filter(classroom -> assignedClassIds.contains(classroom.getClassId()))
                    .toList();
        }
        return toViews(classrooms, orgId);
    }

    /**
     * 반 수정(9차 R6). 이름·정원 중 <b>보낸 것만</b> 바꾼다.
     *
     * <p>이름 중복은 생성과 같은 규칙(같은 기수 안에서 중복이면 409)이되 <b>자기 자신은 빼고</b> 판정한다 —
     * 이름은 그대로 두고 정원만 고치는 경우가 흔한데, 자기 자신을 세면 그때마다 409가 난다.
     *
     * <p><b>정원을 현재 인원보다 작게 두는 것을 막지 않는다.</b> 정원 초과는 배정 경로가 이미 허용하는
     * 상태라(중도 합류·반 통폐합) 여기서만 막으면 규칙이 두 벌이 된다.
     */
    @Transactional
    public ClassroomView updateClassroom(UUID cohortId, UUID classId, UUID orgId, String newName, Integer newCapacity) {
        if (newName == null && newCapacity == null) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_UPDATE_EMPTY);
        }

        Classroom classroom = findClassroomInCohort(cohortId, classId, orgId);

        if (newName != null) {
            String trimmedName = newName.trim();
            if (classroomRepository.existsByCohortIdAndNameAndDeletedAtIsNullAndClassIdNot(cohortId, trimmedName, classId)) {
                throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NAME_TAKEN, "이미 존재하는 반 이름입니다: " + trimmedName);
            }
            classroom.rename(trimmedName);
        }
        if (newCapacity != null) {
            classroom.changeCapacity(newCapacity);
        }

        // 조회해온 엔티티를 수정하면 트랜잭션이 끝날 때 JPA가 알아서 업데이트
        return toView(classroom, orgId);
    }

    /**
     * 반 삭제(9차 R6). 소프트 삭제이며, <b>안에 있던 교육생은 지우지 않고 미배정으로 되돌린다</b> —
     * 명단에서 지우면 그 사람의 등록 이력이 끊긴다. 담당 매니저 배정도 함께 해제한다.
     *
     * <p>삭제 가능 여부는 <b>서버가 판정한다.</b> 화면도 개강 전에만 삭제를 열지만 클라이언트 검증만
     * 있으면 우회되고, 그때 끊기는 것은 학생이 실제로 한 팀 편성과 발행된 리포트다.
     */
    @Transactional
    public void deleteClassroom(UUID cohortId, UUID classId, UUID orgId, UUID actorUserId) {
        Classroom classroom = findClassroomInCohort(cohortId, classId, orgId);
        assertDeletable(classId);

        OffsetDateTime now = OffsetDateTime.now();

        // 소속 교육생을 미배정으로 되돌린다. 배정 이력은 지우지 않고 종료 시각·사유만 남긴다.
        // ck_class_membership_unassigned_reason이 허용하는 코드값 중 운영자의 편성 정정에 해당하는 것이다.
        List<ClassMembership> activeMemberships = classMembershipRepository
                .findByClassIdAndOrgIdAndUnassignedAtIsNull(classId, orgId);
        activeMemberships.forEach(membership -> membership.unassign(now, actorUserId, "ADMIN_CORRECTION"));

        // 담당 매니저도 함께 놓는다 — 없어진 반의 담당으로 남아 있으면 매니저 목록의 담당 반에 계속 잡힌다.
        List<ManagerAssignment> activeAssignments = managerAssignmentRepository
                .findByClassIdAndOrgIdAndUnassignedAtIsNull(classId, orgId);
        activeAssignments.forEach(assignment -> assignment.unassign(now, actorUserId, "CLASS_CLOSED"));

        classroom.softDelete();
    }

    private void assertDeletable(UUID classId) {
        if (classroomDependencyRepository.hasTeams(classId)) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_DELETABLE,
                    "이 반에는 이미 팀이 편성되어 있어 삭제할 수 없습니다. 팀·제출 이력이 이 반에 붙어 있습니다.");
        }
        if (classroomDependencyRepository.hasReports(classId)) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_DELETABLE,
                    "이 반을 대상으로 만들어진 리포트가 있어 삭제할 수 없습니다.");
        }
    }

    // 반이 그 기수 소속인지까지 확인한다. 다른 기수의 반 ID를 넘겨 남의 반을 고치는 경로를 막는다.
    private Classroom findClassroomInCohort(UUID cohortId, UUID classId, UUID orgId) {
        Classroom classroom = findClassroom(classId, orgId);
        if (!classroom.getCohortId().equals(cohortId)) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND);
        }
        return classroom;
    }

    // 교육생 일괄 반 배정: 기존 활성 배정은 해제하고, 대상 반으로 새 배정을 만듦
    @Transactional
    public List<UUID> assignTrainees(UUID cohortId, UUID classroomId, List<UUID> traineeUserIds, UUID orgId,
                                     UUID actorUserId) {
        Classroom classroom = findClassroom(classroomId, orgId);
        if (!classroom.getCohortId().equals(cohortId)) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND);
        }

        List<UUID> distinctTraineeUserIds = traineeUserIds.stream().distinct().toList();

        // leftAtIsNull 조건이 반드시 필요하다. 이게 없으면 두 가지를 막는다:
        //   1) 중도 이탈한 교육생이 반에 배정되는 것
        //   2) 아래 toMap이 IllegalStateException(Duplicate key)을 던지는 것 — 같은 사람이 이탈 후 재등록하면
        //      같은 (cohort_id, user_id)로 cohort_member 행이 2건 생겨 500 에러가 남
        List<CohortMember> cohortMembers = cohortMemberRepository.findByCohortIdAndOrgIdAndUserIdInAndLeftAtIsNull(
                cohortId, orgId, distinctTraineeUserIds);
        Map<UUID, CohortMember> cohortMembersByUserId = cohortMembers.stream()
                .collect(Collectors.toMap(CohortMember::getUserId, Function.identity()));

        // 요청받은 user_id 중 이 기수에 유효하게 소속되지 않은 사람이 있으면 배정 자체를 거부
        List<UUID> missingTraineeIds = distinctTraineeUserIds.stream()
                .filter(userId -> !cohortMembersByUserId.containsKey(userId))
                .toList();
        if (!missingTraineeIds.isEmpty()) {
            throw new ApiException(AcademicOperationsErrorCode.TRAINEE_NOT_IN_COHORT,
                    "기수에 속하지 않은 교육생입니다: " + missingTraineeIds);
        }

        List<UUID> cohortMemberIds = cohortMembers.stream().map(CohortMember::getCohortMemberId).toList();
        List<ClassMembership> activeMemberships = classMembershipRepository
                .findByCohortMemberIdInAndOrgIdAndUnassignedAtIsNull(cohortMemberIds, orgId);

        OffsetDateTime now = OffsetDateTime.now();
        activeMemberships.forEach(membership -> membership.unassign(now, actorUserId, "REASSIGNED"));

        List<ClassMembership> newMemberships = cohortMembers.stream()
                .map(cohortMember -> ClassMembership.builder()
                        .classId(classroomId)
                        .cohortMemberId(cohortMember.getCohortMemberId())
                        .orgId(orgId)
                        .assignedAt(now)
                        .assignedBy(actorUserId)
                        .build())
                .toList();
        classMembershipRepository.saveAll(newMemberships);

        return distinctTraineeUserIds;
    }

    // 교육생 반 배정 되돌리기: 새 배정을 만들지 않고, 현재 활성 배정만 해제함 (assignTrainees의 "이동"과 달리 "취소")
    @Transactional
    public List<UUID> rollbackAssignment(UUID cohortId, List<UUID> traineeUserIds, UUID orgId, UUID actorUserId) {
        List<UUID> distinctTraineeUserIds = traineeUserIds.stream().distinct().toList();
        List<CohortMember> cohortMembers = cohortMemberRepository.findByCohortIdAndOrgIdAndUserIdInAndLeftAtIsNull(
                cohortId, orgId, distinctTraineeUserIds);
        Map<UUID, CohortMember> cohortMembersByUserId = cohortMembers.stream()
                .collect(Collectors.toMap(CohortMember::getUserId, Function.identity()));

        List<UUID> missingTraineeIds = distinctTraineeUserIds.stream()
                .filter(userId -> !cohortMembersByUserId.containsKey(userId))
                .toList();
        if (!missingTraineeIds.isEmpty()) {
            throw new ApiException(AcademicOperationsErrorCode.TRAINEE_NOT_IN_COHORT,
                    "기수에 속하지 않은 교육생입니다: " + missingTraineeIds);
        }

        List<UUID> cohortMemberIds = cohortMembers.stream().map(CohortMember::getCohortMemberId).toList();
        List<ClassMembership> activeMemberships = classMembershipRepository
                .findByCohortMemberIdInAndOrgIdAndUnassignedAtIsNull(cohortMemberIds, orgId);
        Map<UUID, ClassMembership> activeMembershipByCohortMemberId = activeMemberships.stream()
                .collect(Collectors.toMap(ClassMembership::getCohortMemberId, Function.identity()));

        List<UUID> traineesWithoutActiveAssignment = cohortMembers.stream()
                .filter(cohortMember -> !activeMembershipByCohortMemberId.containsKey(cohortMember.getCohortMemberId()))
                .map(CohortMember::getUserId)
                .toList();
        if (!traineesWithoutActiveAssignment.isEmpty()) {
            throw new ApiException(AcademicOperationsErrorCode.ASSIGNMENT_NOT_FOUND,
                    "되돌릴 반 배정이 없는 교육생입니다: " + traineesWithoutActiveAssignment);
        }

        OffsetDateTime now = OffsetDateTime.now();
        activeMemberships.forEach(membership -> membership.unassign(now, actorUserId, "IMMEDIATE_ROLLBACK"));

        return distinctTraineeUserIds;
    }

    // 기수 종료 시 호출: 이 기수에 속한 모든 반 배정·매니저 배정을 일괄 해제 (새 배정은 만들지 않음)
    @Transactional
    public void releaseAllAssignmentsForCohort(UUID cohortId, UUID orgId, UUID actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();

        List<CohortMember> cohortMembers = cohortMemberRepository.findByCohortIdAndOrgIdAndLeftAtIsNull(cohortId, orgId);
        List<UUID> cohortMemberIds = cohortMembers.stream().map(CohortMember::getCohortMemberId).toList();
        List<ClassMembership> activeMemberships = classMembershipRepository
                .findByCohortMemberIdInAndOrgIdAndUnassignedAtIsNull(cohortMemberIds, orgId);
        activeMemberships.forEach(membership -> membership.unassign(now, actorUserId, "COHORT_CLOSED"));

        List<Classroom> classrooms = classroomRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByNameAsc(cohortId, orgId);
        List<UUID> classIds = classrooms.stream().map(Classroom::getClassId).toList();
        List<ManagerAssignment> activeAssignments = managerAssignmentRepository
                .findByClassIdInAndOrgIdAndUnassignedAtIsNull(classIds, orgId);
        activeAssignments.forEach(assignment -> assignment.unassign(now, actorUserId, "COHORT_CLOSED"));
    }

    /**
     * 매니저 계정이 정지될 때 호출: 그 매니저의 활성 담당 배정을 전부 해제한다(새 배정은 만들지 않음).
     *
     * <p>상태만 바꾸고 담당을 두면 그만둔 사람이 반을 붙들고 있어 `담당 매니저 없음` 경고
     * ({@code ClassroomResponse.managerAssignmentRequired})에 잡히지 않는다 — 그 반 학생의 면담·독촉을
     * 아무도 처리하지 않는데 대시보드의 `조치 필요`에도 안 올라간다. <b>경고 체계가 막으려던 상황을
     * 정지 기능이 만드는 셈</b>이라 서버가 같은 트랜잭션에서 함께 푼다(9차 R7).
     *
     * <p>화면이 반마다 {@code PATCH …/managers}를 따로 부르면 중간에 실패했을 때 절반만 풀린다.
     *
     * <p><b>재활성화해도 되돌리지 않는다.</b> 그 사이 다른 사람이 맡았을 수 있고, 되돌릴 것은
     * 배정이지 상태가 아니다(DDL manager_assignment 주석과 같은 규칙).
     *
     * @return 실제로 해제한 배정 건수
     */
    @Transactional
    public int releaseAllAssignmentsForManager(UUID managerUserId, UUID orgId, UUID actorUserId) {
        List<ManagerAssignment> activeAssignments = managerAssignmentRepository
                .findByManagerUserIdAndOrgIdAndUnassignedAtIsNull(managerUserId, orgId);
        OffsetDateTime now = OffsetDateTime.now();
        // ck_manager_assignment_unassigned_reason이 허용하는 코드값이다. 계정 전이로 인한 해제는
        // 운영자가 손으로 뗀 것(MANUAL_UNASSIGN)과 구분돼야 이력에서 사유를 되짚을 수 있다.
        activeAssignments.forEach(assignment -> assignment.unassign(now, actorUserId, "ACCOUNT_INACTIVATED"));
        return activeAssignments.size();
    }

    /**
     * 매니저 한 명의 담당 반을 <b>전체 교체</b>한다(9차 Q3-①). 보낸 목록이 그대로 최종 상태가 된다.
     *
     * <p>{@link #updateClassroomManagers}가 <em>반 하나 = 매니저 여럿</em>인 것과 <b>방향만 반대</b>이고
     * 규칙은 같다 — 둘 다 전체 교체이고, 둘 다 {@code manager_assignment}에 쓴다. 매니저 상세 모달이
     * 담당 반 셋을 체크하면 반 기준 API로는 {@code PATCH}를 반 수만큼 나눠 불러야 하고,
     * <b>중간에 하나가 실패하면 절반만 반영된 상태로 남는다</b>. 이 메서드는 한 트랜잭션이라 그 상태가 없다.
     *
     * <p><b>두 방향이 같은 사실을 두 곳에서 갱신하는 것이 아니다.</b> 갱신 대상은 한 테이블이고,
     * 각자 자기 축(반 / 매니저)의 현재 상태를 통째로 다시 쓴다. 반 A의 담당을 바꾸는 것과
     * 매니저 B의 담당 반을 바꾸는 것은 교집합에서 같은 행을 건드리지만, 마지막에 쓴 쪽이 이기는
     * 전체 교체라 부분 반영이 남지 않는다.
     *
     * @param classroomIds 최종 담당 반 목록. 빈 배열이면 담당을 전부 놓는다
     * @return 실제로 배정된 반 ID 목록(중복 제거 후)
     */
    @Transactional
    public List<UUID> replaceManagerClassrooms(UUID orgId, UUID managerUserId, List<UUID> classroomIds,
                                               UUID actorUserId) {
        List<UUID> distinctClassroomIds = classroomIds == null
                ? List.of()
                : classroomIds.stream().filter(Objects::nonNull).distinct().toList();

        // 넘어온 반이 전부 이 기관 것인지 먼저 확인한다. 하나라도 아니면 아무것도 바꾸지 않고 404다 —
        // 일부만 배정하면 화면이 무엇이 반영됐는지 알 수 없다.
        for (UUID classroomId : distinctClassroomIds) {
            findClassroom(classroomId, orgId);
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 지금 담당 중인 배정을 전부 놓는다. 계속 담당할 반도 일단 놓고 새로 만든다 —
        // "유지되는 것만 남긴다"로 하면 유지 판정이 한 벌 더 생기고, 배정 이력의 의미도 흐려진다.
        List<ManagerAssignment> activeAssignments = managerAssignmentRepository
                .findByManagerUserIdAndOrgIdAndUnassignedAtIsNull(managerUserId, orgId);
        activeAssignments.forEach(assignment -> assignment.unassign(now, actorUserId, "MANUAL_UNASSIGN"));

        if (distinctClassroomIds.isEmpty()) {
            return List.of();
        }

        // 같은 트랜잭션에서 해제와 생성이 함께 일어나야 (manager_user_id, class_id)의 유효 기간이 겹치지 않는다.
        managerAssignmentRepository.flush();

        List<ManagerAssignment> newAssignments = distinctClassroomIds.stream()
                .map(classroomId -> ManagerAssignment.builder()
                        .managerUserId(managerUserId)
                        .orgId(orgId)
                        .classId(classroomId)
                        .assignedAt(now)
                        .status(ASSIGNMENT_STATUS_ACTIVE)
                        .assignedBy(actorUserId)
                        .build())
                .toList();
        managerAssignmentRepository.saveAll(newAssignments);
        return distinctClassroomIds;
    }

    // 반 담당 매니저 변경: 기존 활성 배정은 해제하고, managerIds로 새 배정을 만듦 (빈 목록이면 전체 해제만 수행)
    @Transactional
    public ClassroomView updateClassroomManagers(UUID cohortId, UUID classroomId, UUID orgId,
                                                 List<UUID> managerUserIds, UUID actorUserId) {
        Classroom classroom = findClassroom(classroomId, orgId);
        if (!classroom.getCohortId().equals(cohortId)) {
            throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND);
        }

        replaceClassroomManagers(classroomId, orgId, managerUserIds, actorUserId, OffsetDateTime.now());
        return toView(classroom, orgId);
    }

    // 담당 매니저 전체 교체. 반 생성과 담당 변경이 같은 규칙을 따르도록 한 곳에 모았다.
    // reason은 "MANUAL_UNASSIGN"(담당 매니저 목록 갱신으로 인한 해제) 고정 — DDL CHECK 제약(manager_assignment.unassigned_reason)에 맞는 값
    // @return 실제로 배정된 매니저 user_id 목록(중복 제거된 결과)
    private List<UUID> replaceClassroomManagers(UUID classroomId, UUID orgId,
                                                List<UUID> managerUserIds, UUID actorUserId, OffsetDateTime now) {
        List<ManagerAssignment> activeAssignments = managerAssignmentRepository
                .findByClassIdAndOrgIdAndUnassignedAtIsNull(classroomId, orgId);
        activeAssignments.forEach(assignment -> assignment.unassign(now, actorUserId, "MANUAL_UNASSIGN"));

        List<UUID> distinctManagerUserIds = managerUserIds == null
                ? List.of()
                : managerUserIds.stream().distinct().toList();
        if (distinctManagerUserIds.isEmpty()) {
            return List.of();
        }

        List<ManagerAssignment> newAssignments = distinctManagerUserIds.stream()
                .map(managerUserId -> ManagerAssignment.builder()
                        .managerUserId(managerUserId)
                        .orgId(orgId)
                        .classId(classroomId)
                        .assignedAt(now)
                        .status(ASSIGNMENT_STATUS_ACTIVE)
                        .assignedBy(actorUserId)
                        .build())
                .toList();
        managerAssignmentRepository.saveAll(newAssignments);
        return distinctManagerUserIds;
    }

    // 반 하나를 뷰로 조립. 내부적으로 N+1 방지용 배치 조회 로직(toViews)을 재사용
    private ClassroomView toView(Classroom classroom, UUID orgId) {
        return toViews(List.of(classroom), orgId).get(0);
    }

    // 반 목록을 뷰 목록으로 조립. 매니저 배정·교육생 수 조회를 반 개수만큼 반복하지 않고 각각 한 번씩만 수행
    private List<ClassroomView> toViews(List<Classroom> classrooms, UUID orgId) {
        if (classrooms.isEmpty()) {
            return List.of();
        }
        List<UUID> classIds = classrooms.stream().map(Classroom::getClassId).toList();

        Map<UUID, List<UUID>> managerUserIdsByClassId = managerAssignmentRepository
                .findByClassIdInAndOrgIdAndUnassignedAtIsNull(classIds, orgId).stream()
                .collect(Collectors.groupingBy(ManagerAssignment::getClassId,
                        Collectors.mapping(ManagerAssignment::getManagerUserId, Collectors.toList())));

        Map<UUID, Long> traineeCountByClassId = classMembershipRepository.countActiveByClassIdIn(classIds, orgId).stream()
                .collect(Collectors.toMap(ClassMembershipRepository.ClassTraineeCount::getClassId,
                        ClassMembershipRepository.ClassTraineeCount::getCount));

        // 반 개수만큼 app_user를 되묻지 않도록 등장하는 매니저 전체를 한 번에 읽어 둔다.
        Map<UUID, ManagerDirectoryRepository.ManagerProfile> profileById = managerDirectoryRepository
                .findProfiles(orgId, managerUserIdsByClassId.values().stream().flatMap(List::stream).toList()).stream()
                .collect(Collectors.toMap(ManagerDirectoryRepository.ManagerProfile::memberId, Function.identity()));

        return classrooms.stream()
                .map(classroom -> new ClassroomView(
                        classroom,
                        toProfiles(managerUserIdsByClassId.getOrDefault(classroom.getClassId(), List.of()), profileById),
                        traineeCountByClassId.getOrDefault(classroom.getClassId(), 0L)))
                .toList();
    }

    private List<ManagerDirectoryRepository.ManagerProfile> resolveManagers(UUID orgId, List<UUID> managerUserIds) {
        Map<UUID, ManagerDirectoryRepository.ManagerProfile> profileById = managerDirectoryRepository
                .findProfiles(orgId, managerUserIds).stream()
                .collect(Collectors.toMap(ManagerDirectoryRepository.ManagerProfile::memberId, Function.identity()));
        return toProfiles(managerUserIds, profileById);
    }

    // 배정된 순서를 유지한 채 프로필로 바꾼다. 계정이 지워져 조회되지 않는 사람은 뺀다 —
    // 담당으로 남아 있을 수 없는 사람이라, 빈 칸을 그리는 것보다 `담당 매니저 없음` 판정에 맡기는 편이 맞다.
    private List<ManagerDirectoryRepository.ManagerProfile> toProfiles(
            List<UUID> managerUserIds, Map<UUID, ManagerDirectoryRepository.ManagerProfile> profileById) {
        return managerUserIds.stream()
                .map(profileById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}