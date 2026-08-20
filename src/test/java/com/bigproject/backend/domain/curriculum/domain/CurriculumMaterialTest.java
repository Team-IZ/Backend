package com.bigproject.backend.domain.curriculum.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 삭제 시 제목 봉인(tombstone) — {@code uq_curriculum_material_org_id_normalized_title}가
 * 부분 인덱스가 아니라 전역 UNIQUE인 상태에서도, 삭제된 교안이 제목을 계속 점유하지 않게 한다.
 *
 * <p>{@code materialId}는 {@code @GeneratedValue}라 실제로는 DB에서 읽어온 행에만 값이 있다
 * ({@code softDelete()}도 항상 그런 행에만 불린다) — 테스트에서는 {@link ReflectionTestUtils}로
 * 그 상태를 흉내낸다.
 */
class CurriculumMaterialTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorUserId = UUID.randomUUID();

    /** 삭제하면 normalizedTitle이 materialId 기반의 값으로 바뀌어, 원래 제목과 더 이상 같지 않다. */
    @Test
    void softDeleteTombstonesTheNormalizedTitle() {
        CurriculumMaterial material = CurriculumMaterial.create(
                orgId, "Spring 심화", "spring 심화", "Backend", "PDF", actorUserId);
        ReflectionTestUtils.setField(material, "materialId", UUID.randomUUID());

        material.softDelete();

        assertThat(material.getNormalizedTitle()).isNotEqualTo("spring 심화");
        assertThat(material.getNormalizedTitle()).contains(material.getMaterialId().toString());
        assertThat(material.isDeleted()).isTrue();
        // 화면 표시용 title은 그대로 남는다 — 삭제 이력 조회 등에서 원래 제목을 알아볼 수 있어야 한다.
        assertThat(material.getTitle()).isEqualTo("Spring 심화");
    }

    /** 같은 제목으로 두 번 만들고 각각 지워도, 봉인된 제목끼리 서로 충돌하지 않는다(materialId가 항상 다르므로). */
    @Test
    void tombstonedTitlesFromDifferentMaterialsNeverCollide() {
        CurriculumMaterial first = CurriculumMaterial.create(
                orgId, "AI_LLMOps", "ai_llmops", "AI", "PDF", actorUserId);
        CurriculumMaterial second = CurriculumMaterial.create(
                orgId, "AI_LLMOps", "ai_llmops", "AI", "PDF", actorUserId);
        ReflectionTestUtils.setField(first, "materialId", UUID.randomUUID());
        ReflectionTestUtils.setField(second, "materialId", UUID.randomUUID());

        first.softDelete();
        second.softDelete();

        assertThat(first.getNormalizedTitle()).isNotEqualTo(second.getNormalizedTitle());
    }
}
