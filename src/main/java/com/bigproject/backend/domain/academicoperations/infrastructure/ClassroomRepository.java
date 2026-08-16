package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 여러 반을 한 번에. 팀 목록이 팀마다 반 이름을 부르지 않도록 쓴다(30차 R4).
    List<Classroom> findByClassIdInAndOrgIdAndDeletedAtIsNull(List<UUID> classIds, UUID orgId);

    // 같은 기수 안에 같은 이름의 반이 이미 있는지 확인(반 이름 중복 방지용)
    boolean existsByCohortIdAndNameAndDeletedAtIsNull(UUID cohortId, String name);

    // 반 이름 수정용 중복 확인. 자기 자신은 빼고 본다 — 이름은 그대로 두고 정원만 고치는 경우가 흔한데,
    // 자기 자신을 세면 그때마다 409가 난다(9차 R6).
    boolean existsByCohortIdAndNameAndDeletedAtIsNullAndClassIdNot(UUID cohortId, String name, UUID classId);

    /**
     * 여러 기수의 반 개수를 기수 ID별로 한 번에 센다(13차 Q1).
     *
     * <p>기수 목록이 한 화면에 여러 건 나오므로 기수마다 반 목록을 부르면 조회가 그만큼 늘어난다 —
     * 화면이 `반` 열 하나 때문에 {@code findClassrooms}를 기수 수만큼 부르던 자리다.
     * {@code CohortMemberRepository.countActiveByCohortIdIn}과 같은 방식이다.
     */
    @Query("""
            SELECT c.cohortId AS cohortId, COUNT(c) AS count
            FROM Classroom c
            WHERE c.cohortId IN :cohortIds
                AND c.orgId = :orgId
                AND c.deletedAt IS NULL
            GROUP BY c.cohortId
            """)
    List<CohortClassroomCount> countByCohortIdIn(@Param("cohortIds") List<UUID> cohortIds,
                                                 @Param("orgId") UUID orgId);

    interface CohortClassroomCount {
        UUID getCohortId();

        long getCount();
    }
}
