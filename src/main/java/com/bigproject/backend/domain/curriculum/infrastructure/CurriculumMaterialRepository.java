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
     * <p>종전에는 삭제 여부를 보지 않았다 — {@code uq_curriculum_material_org_id_normalized_title}가
     * 부분 인덱스가 아니라 전역 UNIQUE라 논리 삭제된 교안도 제목을 계속 점유했고, 이 검사도 DB
     * 제약과 같은 기준(전체)으로 맞춰 뒀었다. 42차 R2로 그 제약을
     * {@code WHERE deleted_at IS NULL} 부분 유니크 인덱스로 바꾸면서(§ {@code docs/migration}) 이
     * 앱 레벨 사전 검사도 같은 기준으로 좁힌다 — 여기서만 전역으로 남으면 DB는 허용하는데
     * 이 검사가 먼저 409로 끊어 버려 "지운 제목을 다시 못 쓰는" 문제가 그대로 재현된다.
     */
    boolean existsByOrgIdAndNormalizedTitleAndDeletedAtIsNull(UUID orgId, String normalizedTitle);
}
