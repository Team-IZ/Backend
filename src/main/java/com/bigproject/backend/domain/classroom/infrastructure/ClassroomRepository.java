package com.bigproject.backend.domain.classroom.infrastructure;

import com.bigproject.backend.domain.classroom.domain.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

//클래스 데이터를 DB에서 읽고 쓰는 인터페이스
//메서드 이름만 정해두면 스프링이 알아서 SQL을 만들어 실행.

public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

    // 반 하나 조회. 같은 기관 소속이면서 삭제되지 않은 것
    Optional<Classroom> findByClassIdAndOrgIdAndDeletedAtIsNull(UUID classId, UUID orgId);

    // 특정 기수에 속한 반 목록, 이름순으로 정렬
    List<Classroom> findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByNameAsc(UUID cohortId, UUID orgId);

    // 같은 기수 안에 같은 이름의 반이 이미 있는지 확인(반 이름 중복 방지용)
    boolean existsByCohortIdAndNameAndDeletedAtIsNull(UUID cohortId, String name);
}
