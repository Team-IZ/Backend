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
     * 제목 중복 검사(22차 R2, 42차 R2로 조건 갱신, 2026-08-20 삭제 필터 제거).
     *
     * <p>운영 DB의 {@code uq_curriculum_material_org_id_normalized_title}은 부분 인덱스가 아니라
     * 전역 UNIQUE다(마이그레이션 미적용). 삭제된 행은
     * {@link com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial#softDelete()}가
     * {@code normalized_title}을 {@code __deleted__{materialId}}로 봉인해 이 전역 UNIQUE와 절대
     * 충돌하지 않는 것이 <b>정상 경로의 전제</b>다.
     *
     * <p>🔴 <b>그 전제가 깨진 행이 실제로 있었다.</b> API가 아니라 raw SQL로 {@code deleted_at}만
     * 찍고 {@code normalized_title}은 안 건드린 행들이 org당 수십 건 쌓여, 그 제목으로 새로
     * 등록하면 이 검사(당시엔 {@code AndDeletedAtIsNull})는 통과하는데 DB INSERT가
     * {@code DataIntegrityViolationException}으로 터졌다 — 도메인 예외
     * ({@code CURRICULUM_TITLE_DUPLICATED})가 아니라 전역 예외 처리기의 fallback
     * ({@code DATA_INTEGRITY_VIOLATION})으로 새 나가 화면에 엉뚱한 문구가 떴다(실제 재현,
     * 2026-08-20 — POST /curricula, "AI_데이터 분석_교안").
     *
     * <p>그래서 <b>삭제 여부와 무관하게</b> 전역 UNIQUE와 같은 범위로 본다 — 봉인이 실제로 됐는지는
     * 검사가 신경 쓸 일이 아니라, 검사 범위가 DB 제약과 같아야 할 일이다.
     */
    boolean existsByOrgIdAndNormalizedTitle(UUID orgId, String normalizedTitle);
}
