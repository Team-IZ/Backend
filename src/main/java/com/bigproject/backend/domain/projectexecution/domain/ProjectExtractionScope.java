package com.bigproject.backend.domain.projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// checkpoint별 코드 추출 범위 오버라이드 이력. project.defaultExtractionScopeCode가
// 기본값이고, 특정 회차만 다르게 하고 싶을 때 여기에 새 버전을 추가한다
@Entity
@Table(name = "project_extraction_scope")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectExtractionScope {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "extraction_scope_id", nullable = false, updatable = false)
    private UUID extractionScopeId;

    @Column(name = "checkpoint_id", nullable = false, updatable = false)
    private UUID checkpointId;

    // ===== 2묶음: 업무 필드 =====

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_code", nullable = false, length = 100)
    private ExtractionScope scopeCode;

    // project의 기본값을 그대로 물려받았는지(true), checkpoint에서 따로 지정했는지(false)
    @Column(name = "inherited_from_default", nullable = false)
    private boolean inheritedFromDefault;

    @Column(name = "version_no", nullable = false, updatable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private ProjectExtractionScope(UUID checkpointId, ExtractionScope scopeCode,
                                   boolean inheritedFromDefault, Integer versionNo, OffsetDateTime effectiveFrom,
                                   UUID changedBy) {
        this.checkpointId = checkpointId;
        this.scopeCode = scopeCode;
        this.inheritedFromDefault = inheritedFromDefault;
        this.versionNo = versionNo;
        this.effectiveFrom = effectiveFrom;
        this.changedBy = changedBy;
    }

    // 이 버전을 종료. 지우지 않고 종료 시각만 찍는다 — TeamMembership.unassign과 동일 패턴
    public void expire(OffsetDateTime effectiveTo) {
        if (this.effectiveTo != null) {
            return; // 멱등
        }
        if (effectiveTo.isBefore(this.effectiveFrom)) {
            throw new IllegalArgumentException("종료 시각이 시작 시각보다 이릅니다.");
        }
        this.effectiveTo = effectiveTo;
    }

    public boolean isEffectiveAt(OffsetDateTime at) {
        if (at.isBefore(this.effectiveFrom)) {
            return false;
        }
        return this.effectiveTo == null || at.isBefore(this.effectiveTo);
    }
}
