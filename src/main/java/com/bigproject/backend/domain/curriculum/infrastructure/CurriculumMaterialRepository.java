package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurriculumMaterialRepository extends JpaRepository<CurriculumMaterial, UUID> {

    Optional<CurriculumMaterial> findByMaterialIdAndOrgId(UUID materialId, UUID orgId);

    // 논리 삭제된 교안은 신규 버전·프로젝트 연결 대상에서 제외 — 목록 화면은 이걸 쓴다
    List<CurriculumMaterial> findAllByOrgIdAndDeletedAtIsNull(UUID orgId);

    boolean existsByMaterialIdAndOrgId(UUID materialId, UUID orgId);
}
