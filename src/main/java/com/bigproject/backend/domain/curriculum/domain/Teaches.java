package com.bigproject.backend.domain.curriculum.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "teaches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Teaches {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "teaches_id", nullable = false, updatable = false)
    private UUID teachesId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "canonical_name", nullable = false, length = 200)
    private String canonicalName;

    @Column(name = "normalized_name", nullable = false, length = 200)
    private String normalizedName;

    @Column(name = "canonical_description", nullable = false)
    private String canonicalDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TeachesStatus status;

    @Column(name = "merged_into_teaches_id")
    private UUID mergedIntoTeachesId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    private Teaches(UUID orgId, String canonicalName, String normalizedName,
                    String canonicalDescription, UUID createdBy) {
        OffsetDateTime now = OffsetDateTime.now();
        this.orgId = orgId;
        this.canonicalName = canonicalName;
        this.normalizedName = normalizedName;
        this.canonicalDescription = canonicalDescription;
        this.status = TeachesStatus.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Teaches create(UUID orgId, String canonicalName, String normalizedName,
                                 String canonicalDescription, UUID createdBy) {
        return Teaches.builder()
                .orgId(orgId)
                .canonicalName(canonicalName)
                .normalizedName(normalizedName)
                .canonicalDescription(canonicalDescription)
                .createdBy(createdBy)
                .build();
    }

    public void mergeInto(UUID targetTeachesId) {
        if (targetTeachesId.equals(this.teachesId)) {
            throw new IllegalArgumentException("자기 자신으로 병합할 수 없습니다.");
        }
        if (this.status == TeachesStatus.MERGED) {
            throw new IllegalStateException("이미 병합된 개념입니다.");
        }
        this.status = TeachesStatus.MERGED;
        this.mergedIntoTeachesId = targetTeachesId;
        this.updatedAt = OffsetDateTime.now();
    }

    public void deactivate() {
        if (this.status == TeachesStatus.MERGED) {
            throw new IllegalStateException("병합된 개념은 비활성화할 수 없습니다.");
        }
        this.status = TeachesStatus.INACTIVE;
        this.updatedAt = OffsetDateTime.now();
    }
}