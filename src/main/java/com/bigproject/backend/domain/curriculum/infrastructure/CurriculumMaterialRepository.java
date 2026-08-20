package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface
CurriculumMaterialRepository extends JpaRepository<CurriculumMaterial, UUID> {

    Optional<CurriculumMaterial> findByMaterialIdAndOrgId(UUID materialId, UUID orgId);

    // 논리 삭제된 교안은 신규 버전·프로젝트 연결 대상에서 제외 — 목록 화면은 이걸 쓴다
    List<CurriculumMaterial> findAllByOrgIdAndDeletedAtIsNull(UUID orgId);

    boolean existsByMaterialIdAndOrgId(UUID materialId, UUID orgId);

    /**
     * 제목 중복 검사(22차 R2, 42차 R2로 조건 갱신). <b>삭제되지 않은 교안만 본다.</b>
     *
     * <p>운영 DB의 {@code uq_curriculum_material_org_id_normalized_title}은 부분 인덱스가 아니라
     * 여전히 전역 UNIQUE다(마이그레이션 미적용). 그런데도 이 검사가 안전한 이유는 마이그레이션이
     * 아니라 {@link com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial#softDelete()}
     * 쪽에서 해결했기 때문이다 — 삭제되는 행의 {@code normalized_title}을 그 자리에서 유일한 값으로
     * 봉인해서, 살아있는 새 교안과 절대 충돌하지 않는다. 그래서 이 검사가 삭제된 행을 걸러내도(살아
     * 있는 것만 봐도) DB의 전역 UNIQUE와 어긋나지 않는다.
     */
    boolean existsByOrgIdAndNormalizedTitleAndDeletedAtIsNull(UUID orgId, String normalizedTitle);
}
