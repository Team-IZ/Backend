package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.MeasurementPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MeasurementPlanRepository extends JpaRepository<MeasurementPlan, UUID> {

    // project 하나당 계획은 정확히 하나(DB에 UNIQUE 제약) — 그래서 List가 아니라 단건 Optional
    Optional<MeasurementPlan> findByProjectIdAndOrgId(UUID projectId, UUID orgId);
}
