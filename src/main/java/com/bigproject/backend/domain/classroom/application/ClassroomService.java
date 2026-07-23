package com.bigproject.backend.domain.classroom.application;

import com.bigproject.backend.domain.classroom.domain.Classroom;
import com.bigproject.backend.domain.classroom.infrastructure.ClassroomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// 반 관련 업무 규칙을 처리하는 서비스
// 조회는 기본이 읽기 전용이고, 데이터를 바꾸는 메서드에만 따로 @Transactional을 붙임
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassroomService {

    private final ClassroomRepository classroomRepository;

    @Transactional
    public Classroom createClassroom(UUID orgId, UUID cohortId, String name, UUID creatorUserId) {
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

        return classroomRepository.save(classroom);
    }

    public Classroom findClassroom(UUID classId, UUID orgId) {
        return classroomRepository.findByClassIdAndOrgIdAndDeletedAtIsNull(classId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."));
    }

    public List<Classroom> findClassrooms(UUID cohortId, UUID orgId) {
        return classroomRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByNameAsc(cohortId, orgId);
    }

    @Transactional
    public Classroom renameClassroom(UUID classId, UUID orgId, String newName) {
        Classroom classroom = findClassroom(classId, orgId);
        classroom.rename(newName);
        // 조회해온 엔티티를 수정하면 트랜잭션이 끝날 때 JPA가 알아서 업데이트
        return classroom;
    }

    @Transactional
    public void deleteClassroom(UUID classId, UUID orgId) {
        Classroom classroom = findClassroom(classId, orgId);
        classroom.softDelete();
    }
}