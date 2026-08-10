package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.Teaches;
import com.bigproject.backend.domain.curriculum.domain.TeachesStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeachesRepository extends JpaRepository<Teaches, UUID> {
    Optional<Teaches> findByTeachesIdAndOrgId(UUID teachesId, UUID orgId);

    // 기관 범위 활성 개념 목록 — 병합 중복 판정(normalized_name)에도 쓰인다
    List<Teaches> findAllByOrgIdAndStatus(UUID orgId, TeachesStatus status);

    Optional<Teaches> findByOrgIdAndNormalizedNameAndStatus(UUID orgId, String normalizedName, TeachesStatus status);

    Optional<Teaches> findByOrgIdAndNormalizedName(UUID orgId, String normalizedName);
}