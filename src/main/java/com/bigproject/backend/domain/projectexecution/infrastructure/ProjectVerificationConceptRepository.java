package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectVerificationConcept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectVerificationConceptRepository extends JpaRepository<ProjectVerificationConcept, UUID> {

    // "쓰인 회차" 조회용 — 이 teachesId가 검증 개념으로 쓰인 모든 세트를 찾는다
    List<ProjectVerificationConcept> findByTeachesId(UUID teachesId);
}