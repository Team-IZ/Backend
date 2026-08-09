package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ConceptSetStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectVerificationConceptSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectVerificationConceptSetRepository extends JpaRepository<ProjectVerificationConceptSet, UUID> {

    Optional<ProjectVerificationConceptSet> findByProjectIdAndStatus(UUID projectId, ConceptSetStatus status);

    /**
     * 이 프로젝트가 쓴 가장 큰 버전 번호. 세트가 하나도 없으면 0이다.
     *
     * <p>활성 세트의 {@code versionNo + 1}이 아니라 <b>최대값 + 1</b>로 채번해야 한다.
     * {@code uq_project_verification_concept_set_project_id_version_no}가 상태를 가리지 않으므로,
     * 활성 세트가 없는데 SUPERSEDED 이력이 남아 있는 상태에서 1부터 다시 매기면 그 이력과 충돌한다.
     */
    @Query("SELECT COALESCE(MAX(s.versionNo), 0) FROM ProjectVerificationConceptSet s WHERE s.projectId = :projectId")
    int findMaxVersionNo(@Param("projectId") UUID projectId);

    /** 여러 프로젝트의 세트를 한 번에(11차 R1·R3). 프로젝트마다 따로 읽으면 목록에서 N+1이 된다. */
    List<ProjectVerificationConceptSet> findByProjectIdInAndStatus(Collection<UUID> projectIds, ConceptSetStatus status);
}