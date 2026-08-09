package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectVerificationConcept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectVerificationConceptRepository extends JpaRepository<ProjectVerificationConcept, UUID> {

    // "쓰인 회차" 조회용 — 이 teachesId가 검증 개념으로 쓰인 모든 세트를 찾는다
    List<ProjectVerificationConcept> findByTeachesId(UUID teachesId);

    /**
     * 위 조회의 일괄 판(11차 R1). 교안 섹션 조회가 항목마다 이것을 부르고, 그 안에서 다시
     * 세트·프로젝트를 한 건씩 읽어 쿼리가 수백 건으로 불어났다 — 항목 전부를 한 번에 읽는다.
     */
    List<ProjectVerificationConcept> findByTeachesIdIn(Collection<UUID> teachesIds);

    /**
     * 확정된 검증 개념 되읽기용(9차 R1). 활성 세트 하나 안의 개념을 확정할 때의 순서 그대로 준다 —
     * 화면이 개념을 칩으로 나열하는 순서가 저장 순서와 어긋나면 안 된다.
     */
    List<ProjectVerificationConcept> findByConceptSetIdOrderBySequenceNoAsc(UUID conceptSetId);

    /** 위 조회의 여러 세트 판(11차 R3). 세트마다 따로 읽으면 회차 목록에서 N+1이 된다. */
    List<ProjectVerificationConcept> findByConceptSetIdInOrderBySequenceNoAsc(Collection<UUID> conceptSetIds);

    long countByConceptSetId(UUID conceptSetId);
}