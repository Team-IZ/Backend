package com.bigproject.backend.domain.classroom.application;

import com.bigproject.backend.domain.classroom.domain.ClassMembership;
import com.bigproject.backend.domain.classroom.domain.Classroom;
import com.bigproject.backend.domain.classroom.domain.ManagerAssignment;
import com.bigproject.backend.domain.classroom.domain.RoleScope;
import com.bigproject.backend.domain.classroom.infrastructure.ClassMembershipRepository;
import com.bigproject.backend.domain.classroom.infrastructure.ClassroomRepository;
import com.bigproject.backend.domain.classroom.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.cohort.domain.CohortMember;
import com.bigproject.backend.domain.cohort.infrastructure.CohortMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 반 관련 업무 규칙을 처리하는 서비스
// 조회는 기본이 읽기 전용이고, 데이터를 바꾸는 메서드에만 따로 @Transactional을 붙임
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassroomService {

    private final ClassroomRepository classroomRepository;
    private final CohortMemberRepository cohortMemberRepository;
    private final ClassMembershipRepository classMembershipRepository;
    private final ManagerAssignmentRepository managerAssignmentRepository;

    // 반 하나에 대한 응답 조립용 뷰: 담당 매니저 user_id 목록과 활성 교육생 수를 함께 담음
    // 매니저 이름은 app_user 조인이 필요해 member 도메인 의존이 생기므로 여기서는 채우지 않음 (memberId만 제공)
    public record ClassroomView(Classroom classroom, List<UUID> managerUserIds, long traineeCount) {
    }

    @Transactional
    public ClassroomView createClassroom(UUID orgId, UUID cohortId, String name, UUID creatorUserId) {
        // 같은 기수 안에서 반 이름이 겹치면 안 되는데 DB에 제약이 없어서 여기서 확인
        if (classroomRepository.existsByCohortIdAndNameAndDeletedAtIsNull(cohortId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 반 이름입니다: " + name);
        }

        Classroom classroom = Classroom.builder()
                .orgId(orgId)
                .cohortId(cohortId)
                .name(name)
                .createdBy(creatorUserId)
                .build();
        classroomRepository.save(classroom);

        // 방금 만든 반이라 담당 매니저·교육생이 있을 수 없으므로 조회 없이 바로 빈 값으로 조립
        return new ClassroomView(classroom, List.of(), 0);
    }

    public Classroom findClassroom(UUID classId, UUID orgId) {
        return classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."));
    }

    public ClassroomView findClassroomView(UUID classId, UUID orgId) {
        return toView(findClassroom(classId, orgId), orgId);
    }

    public List<ClassroomView> findClassroomViews(UUID cohortId, UUID orgId) {
        List<Classroom> classrooms = classroomRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByNameAsc(cohortId, orgId);
        return toViews(classrooms, orgId);
    }

    @Transactional
    public ClassroomView renameClassroom(UUID classId, UUID orgId, String newName) {
        Classroom classroom = findClassroom(classId, orgId);
        classroom.rename(newName);
        // 조회해온 엔티티를 수정하면 트랜잭션이 끝날 때 JPA가 알아서 업데이트
        return toView(classroom, orgId);
    }

    @Transactional
    public void deleteClassroom(UUID classId, UUID orgId) {
        Classroom classroom = findClassroom(classId, orgId);
        classroom.softDelete();
    }

    // 교육생 일괄 반 배정: 기존 활성 배정은 해제하고, 대상 반으로 새 배정을 만듦
    @Transactional
    public List<UUID> assignTrainees(UUID cohortId, UUID classroomId, List<UUID> traineeUserIds, UUID orgId,
            UUID actorUserId) {
        Classroom classroom = findClassroom(classroomId, orgId);
        if (!classroom.getCohortId().equals(cohortId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다.");
        }

        List<UUID> distinctTraineeUserIds = traineeUserIds.stream().distinct().toList();
        List<CohortMember> cohortMembers = cohortMemberRepository.findByCohortIdAndOrgIdAndUserIdIn(
                cohortId, orgId, distinctTraineeUserIds);
        Map<UUID, CohortMember> cohortMembersByUserId = cohortMembers.stream()
                .collect(Collectors.toMap(CohortMember::getUserId, Function.identity()));

        // 요청받은 user_id 중 이 기수에 소속되지 않은 사람이 있으면 배정 자체를 거부
        List<UUID> missingTraineeIds = distinctTraineeUserIds.stream()
                .filter(userId -> !cohortMembersByUserId.containsKey(userId))
                .toList();
        if (!missingTraineeIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "기수에 속하지 않은 교육생입니다: " + missingTraineeIds);
        }

        List<UUID> cohortMemberIds = cohortMembers.stream().map(CohortMember::getCohortMemberId).toList();
        List<ClassMembership> activeMemberships = classMembershipRepository
                .findByCohortMemberIdInAndOrgIdAndUnassignedAtIsNull(cohortMemberIds, orgId);

        OffsetDateTime now = OffsetDateTime.now();
        activeMemberships.forEach(membership -> membership.unassign(now));

        String batchId = UUID.randomUUID().toString();
        List<ClassMembership> newMemberships = cohortMembers.stream()
                .map(cohortMember -> ClassMembership.builder()
                        .classId(classroomId)
                        .cohortMemberId(cohortMember.getCohortMemberId())
                        .orgId(orgId)
                        .assignedAt(now)
                        .assignmentBatchId(batchId)
                        .assignedBy(actorUserId)
                        .build())
                .toList();
        classMembershipRepository.saveAll(newMemberships);

        return distinctTraineeUserIds;
    }

    // 반 담당 매니저 변경: 기존 활성 배정은 해제하고, managerIds로 새 배정을 만듦 (빈 목록이면 전체 해제만 수행)
    @Transactional
    public ClassroomView updateClassroomManagers(UUID cohortId, UUID classroomId, UUID orgId,
            List<UUID> managerUserIds, UUID actorUserId) {
        Classroom classroom = findClassroom(classroomId, orgId);
        if (!classroom.getCohortId().equals(cohortId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다.");
        }

        List<ManagerAssignment> activeAssignments = managerAssignmentRepository
                .findByClassIdAndOrgIdAndUnassignedAtIsNull(classroomId, orgId);
        OffsetDateTime now = OffsetDateTime.now();
        activeAssignments.forEach(assignment -> assignment.unassign(now));

        List<UUID> distinctManagerUserIds = managerUserIds.stream().distinct().toList();
        List<ManagerAssignment> newAssignments = distinctManagerUserIds.stream()
                .map(managerUserId -> ManagerAssignment.builder()
                        .managerUserId(managerUserId)
                        .orgId(orgId)
                        .roleScope(RoleScope.CLASS)
                        .cohortId(cohortId)
                        .classId(classroomId)
                        .assignedAt(now)
                        .status("ACTIVE")
                        .assignedBy(actorUserId)
                        .build())
                .toList();
        managerAssignmentRepository.saveAll(newAssignments);

        return toView(classroom, orgId);
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

        return classrooms.stream()
                .map(classroom -> new ClassroomView(
                        classroom,
                        managerUserIdsByClassId.getOrDefault(classroom.getClassId(), List.of()),
                        traineeCountByClassId.getOrDefault(classroom.getClassId(), 0L)))
                .toList();
    }
}
