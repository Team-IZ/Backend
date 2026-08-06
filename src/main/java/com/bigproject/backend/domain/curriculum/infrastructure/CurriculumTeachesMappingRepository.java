package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.MappingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurriculumTeachesMappingRepository extends JpaRepository<CurriculumTeachesMapping, UUID> {

    List<CurriculumTeachesMapping> findAllBySourceAnalysisIdOrderBySequenceNoAsc(UUID sourceAnalysisId);

    Optional<CurriculumTeachesMapping> findByMappingIdAndOrgId(UUID mappingId, UUID orgId);

    /**
     * project 도메인의 GET /cohorts/{id}/curricula가 쓸 핵심 조회.
     * 승인된(ACTIVE) 매핑만 후보로 준다 — 검토 안 끝난 개념이 학생 문항이 되면 안 되므로.
     * versionId 하나를 대상으로 하고, project 쪽에서 연결된 여러 교안에 대해 이걸 반복 호출한다.
     */
    @Query("""
            select tm from CurriculumTeachesMapping tm
            where tm.versionId = :versionId
              and tm.orgId = :orgId
              and tm.mappingStatus = :status
            order by tm.sequenceNo asc
            """)
    List<CurriculumTeachesMapping> findActiveCandidatesByVersion(
            @Param("versionId") UUID versionId,
            @Param("orgId") UUID orgId,
            @Param("status") MappingStatus status);

    /**
     * project_verification_concept 생성 시 검증용:
     * source_mapping_id가 가리키는 teaches_id가 실제로 이 매핑의 teaches_id와 일치하는지,
     * 그리고 이 매핑이 프로젝트에 연결된 정확한 curriculum_version_id 범위 안에 있는지 확인한다.
     */
    Optional<CurriculumTeachesMapping> findByMappingIdAndTeachesIdAndVersionIdAndOrgIdAndMappingStatus(
            UUID mappingId, UUID teachesId, UUID versionId, UUID orgId, MappingStatus mappingStatus);

    /**
     * MG-09 ②③④ 섹션 상세 탭용 — 이 섹션 안의 매핑(가르친 항목) 전체를 문항 순서대로.
     * "★가 검증 개념으로 쓰인 항목" 표시(②④)와 "★ 없이 항목만"(③)을 가르는 것은
     * 이 결과를 project 도메인의 findRoundLabelsUsingTeaches로 하나씩 대조해야 알 수 있다 —
     * 이 쿼리 자체는 섹션 안의 후보 전체만 돌려준다.
     */
    List<CurriculumTeachesMapping> findAllBySectionIdOrderBySequenceNoAsc(UUID sectionId);
}