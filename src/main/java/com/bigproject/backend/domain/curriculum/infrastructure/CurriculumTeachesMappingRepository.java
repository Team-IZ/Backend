package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.MappingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * 위 조회의 개수만 필요한 자리(목록 화면의 `후보 12건에서 3건`)에서 쓴다.
     * 항목마다 매핑 전량을 받아 세면 목록 하나에 교안 전량이 따라 올라온다(9차 R1).
     */
    @Query("""
            select count(tm) from CurriculumTeachesMapping tm
            where tm.versionId = :versionId
              and tm.orgId = :orgId
              and tm.mappingStatus = :status
            """)
    long countActiveCandidatesByVersion(
            @Param("versionId") UUID versionId,
            @Param("orgId") UUID orgId,
            @Param("status") MappingStatus status);

    /**
     * 위 개수 조회의 일괄 판(15차 R1). 회차 목록이 교안 버전마다 이것을 부르고 있었다 —
     * 버전 전체를 한 번에 세고 호출부가 버전별로 나눈다.
     *
     * <p>{@code versionId}가 없는 버전은 <b>행 자체가 나오지 않는다</b>(GROUP BY라 0건은 그룹이
     * 생기지 않는다). 호출부는 없는 키를 0으로 읽어야 한다.
     *
     * @return {@code [versionId, count]} 두 칸짜리 배열의 목록
     */
    @Query("""
            select tm.versionId, count(tm) from CurriculumTeachesMapping tm
            where tm.versionId in :versionIds
              and tm.orgId = :orgId
              and tm.mappingStatus = :status
            group by tm.versionId
            """)
    List<Object[]> countActiveCandidatesByVersionIds(
            @Param("versionIds") Collection<UUID> versionIds,
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

    /**
     * 위 조회의 여러 섹션 판(11차 R1). 섹션 목록 화면이 섹션마다 이것을 부르고 있었다 —
     * 섹션 수만큼 쿼리가 나가므로 한 번에 읽고 호출부에서 섹션별로 나눈다.
     */
    List<CurriculumTeachesMapping> findAllBySectionIdInOrderBySequenceNoAsc(Collection<UUID> sectionIds);
}