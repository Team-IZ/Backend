package com.bigproject.backend.domain.academicoperations.domain;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cohort")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cohort {

    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CohortStatus status;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "retention_until")
    private OffsetDateTime retentionUntil;

    @Column(name = "retention_policy_id")
    private UUID retentionPolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disclosure_scope", nullable = false, length = 30)
    private DisclosureScope disclosureScope;

    @Column(name = "disclosure_policy_id", nullable = false)
    private UUID disclosurePolicyId;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private Cohort(UUID orgId, String name, LocalDate startDate, LocalDate endDate,
                   UUID createdBy, DisclosureScope disclosureScope, UUID disclosurePolicyId) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        this.orgId = orgId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = CohortStatus.PLANNED;
        this.createdBy = createdBy;
        this.disclosureScope = disclosureScope;
        this.disclosurePolicyId = disclosurePolicyId;
    }

    /** 기수 종료 */
    public void close(UUID actorUserId) {
        this.status = CohortStatus.CLOSED;
        this.closedAt = OffsetDateTime.now();
        this.updatedBy = actorUserId;
    }

    /** 소프트 삭제 */
    public void softDelete(UUID actorUserId) {
        this.deletedAt = OffsetDateTime.now();
        this.updatedBy = actorUserId;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
