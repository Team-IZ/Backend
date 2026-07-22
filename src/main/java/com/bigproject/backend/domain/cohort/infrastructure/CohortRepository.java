package com.bigproject.backend.domain.cohort.infrastructure;

import com.bigproject.backend.domain.cohort.domain.Cohort;
import com.bigproject.backend.domain.cohort.domain.CohortStatus;
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

    /**기수 목록 조회 — 상태/검색어는 null이면 조건 무시 */
    @Query("""
            SELECT c FROM Cohort c
            WHERE c.orgId = :orgId
              AND c.deletedAt IS NULL
              AND (:status IS NULL OR c.status = :status)
              AND (:query IS NULL OR c.name LIKE CONCAT('%', :query, '%'))
            ORDER BY c.startDate DESC
            """)
    Page<Cohort> findCohorts(@Param("orgId") UUID orgId,
                             @Param("status") CohortStatus status,
                             @Param("query") String query,
                             Pageable pageable);
}
