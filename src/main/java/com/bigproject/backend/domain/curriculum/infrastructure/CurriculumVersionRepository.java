package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurriculumVersionRepository extends JpaRepository<CurriculumVersion, UUID> {

    List<CurriculumVersion> findAllByMaterialIdOrderByVersionNoDesc(UUID materialId);

    // 다음 version_no 채번용. materialId 기준 잠금 후 이 값+1을 넘긴다(동시성은 UNIQUE(material_id, version_no)가 최종 방어).
    @Query("select coalesce(max(v.versionNo), 0) from CurriculumVersion v where v.materialId = :materialId")
    int findMaxVersionNo(@Param("materialId") UUID materialId);

    // 동일 파일 내용 중복 버전 방지 — 등록 전 이걸로 확인
    Optional<CurriculumVersion> findByMaterialIdAndContentHash(UUID materialId, String contentHash);

    // org_id 컬럼이 없는 테이블이라 material을 거쳐 테넌트를 확인한다.
    @Query("""
            select v from CurriculumVersion v
            join CurriculumMaterial m on m.materialId = v.materialId
            where v.versionId = :versionId and m.orgId = :orgId
            """)
    Optional<CurriculumVersion> findByVersionIdAndOrgId(@Param("versionId") UUID versionId, @Param("orgId") UUID orgId);

    // 프로젝트 생성 모달의 "교안 연결" 후보 — 기관 범위, 활성 버전만
    @Query("""
            select v from CurriculumVersion v
            join CurriculumMaterial m on m.materialId = v.materialId
            where m.orgId = :orgId and v.status = :status and m.deletedAt is null
            """)
    List<CurriculumVersion> findAllActiveByOrgId(@Param("orgId") UUID orgId, @Param("status") CurriculumVersionStatus status);
}