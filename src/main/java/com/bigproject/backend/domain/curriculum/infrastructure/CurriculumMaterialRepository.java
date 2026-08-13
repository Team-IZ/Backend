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
     * 제목 중복 검사(22차 R2). <b>삭제 여부를 보지 않는다</b> —
     * {@code uq_curriculum_material_org_id_normalized_title}가 부분 인덱스가 아니라 전역 UNIQUE라
     * 논리 삭제된 교안도 제목을 계속 점유한다. 살아 있는 것만 세면 검사는 통과하고 INSERT가
     * DB에서 터져 코드 없는 500이 난다({@code ProjectRepository}의 회차 이름과 같은 함정이다).
     */
    boolean existsByOrgIdAndNormalizedTitle(UUID orgId, String normalizedTitle);
}
