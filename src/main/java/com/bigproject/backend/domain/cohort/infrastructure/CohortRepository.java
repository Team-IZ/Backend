package com.bigproject.backend.domain.cohort.infrastructure;

import com.bigproject.backend.domain.cohort.domain.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    /** 기수 단건 조회 (해당 기관 소속 + 삭제 안 된 것만) */
    Optional<Cohort> findByCohortIdAndOrgIdAndDeletedAtIsNull(UUID cohortId, UUID orgId);

    /** 기관 내 동일 이름의 살아있는 기수 존재 여부 (기수명 중복 검사용) */
    boolean existsByOrgIdAndNameAndDeletedAtIsNull(UUID orgId, String name);
}
git add