package com.bigproject.backend.domain.curriculum.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "curriculum_material")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "normalized_title", nullable = false, length = 200)
    private String normalizedTitle;

    @Column(name = "topic")
    private String topic;

    @Column(name = "material_type", length = 100)
    private String materialType;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    private CurriculumMaterial(UUID orgId, String title, String normalizedTitle,
                               String topic, String materialType, UUID createdBy) {
        OffsetDateTime now = OffsetDateTime.now();
        this.orgId = orgId;
        this.title = title;
        this.normalizedTitle = normalizedTitle;
        this.topic = topic;
        this.materialType = materialType;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static CurriculumMaterial create(UUID orgId, String title, String normalizedTitle,
                                            String topic, String materialType, UUID createdBy) {
        return CurriculumMaterial.builder()
                .orgId(orgId).title(title).normalizedTitle(normalizedTitle)
                .topic(topic).materialType(materialType).createdBy(createdBy)
                .build();
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    /** 42차 R1 — 새 버전을 올리며 제목을 바꿔 달 때만 쓴다(선택). material 자체는 그대로다. */
    public void updateTitle(String title, String normalizedTitle) {
        this.title = title;
        this.normalizedTitle = normalizedTitle;
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}