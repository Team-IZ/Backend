package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    /** 기수 단건 조회 (해당 기관 소속 + 삭제 안 된 것만) */
    Optional<Cohort> findByCohortIdAndOrgIdAndDeletedAtIsNull(UUID cohortId, UUID orgId);

    /** 기관 내 동일 이름의 살아있는 기수 존재 여부 (기수명 중복 검사용) */
    boolean existsByOrgIdAndNameAndDeletedAtIsNull(UUID orgId, String name);

    /**
     * 기수 존재 여부만 확인한다(22차 R7). 엔티티를 통째로 읽을 이유가 없는 검증용이다.
     *
     * <p>{@code orgId}를 함께 거는 것은 남의 기관 기수의 존재 여부를 알려 주지 않기 위해서다 —
     * 있으면 403, 없으면 404로 갈리면 그 차이로 다른 기관의 기수 ID를 확인할 수 있다.
     */
    boolean existsByCohortIdAndOrgIdAndDeletedAtIsNull(UUID cohortId, UUID orgId);

    /**기수 목록 조회 — 상태/검색어는 null이면 조건 무시 */
    @Query("""
            SELECT c FROM Cohort c
            WHERE c.orgId = :orgId
              AND c.deletedAt IS NULL
              AND (:status IS NULL OR c.status = :status)
              AND (CAST(:query AS string) IS NULL OR c.name LIKE CONCAT('%', CAST(:query AS string), '%'))
            ORDER BY c.startDate DESC
            """)
    Page<Cohort> findCohorts(@Param("orgId") UUID orgId,
                             @Param("status") CohortStatus status,
                             @Param("query") String query,
                             Pageable pageable);
}
