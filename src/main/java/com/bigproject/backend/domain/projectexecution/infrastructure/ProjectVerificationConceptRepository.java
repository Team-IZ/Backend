package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectVerificationConcept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectVerificationConceptRepository extends JpaRepository<ProjectVerificationConcept, UUID> {

    // "쓰인 회차" 조회용 — 이 teachesId가 검증 개념으로 쓰인 모든 세트를 찾는다
    List<ProjectVerificationConcept> findByTeachesId(UUID teachesId);

    /**
     * 확정된 검증 개념 되읽기용(9차 R1). 활성 세트 하나 안의 개념을 확정할 때의 순서 그대로 준다 —
     * 화면이 개념을 칩으로 나열하는 순서가 저장 순서와 어긋나면 안 된다.
     */
    List<ProjectVerificationConcept> findByConceptSetIdOrderBySequenceNoAsc(UUID conceptSetId);

    long countByConceptSetId(UUID conceptSetId);
}