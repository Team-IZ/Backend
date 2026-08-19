package com.bigproject.backend.domain.academicoperations.domain;

import com.bigproject.backend.domain.organization.domain.DisclosureScope;
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

    /** 기수 종료. retentionPolicyId·retentionUntil은 DB CHECK(ck_cohort_closed)가 CLOSED 전이 시 필수로 요구한다.
     *  종료 시점의 기관 정책 버전을 스냅샷으로 고정하며, 이후 정책이 바뀌어도 이 값은 갱신하지 않는다. */
    public void close(UUID actorUserId, UUID retentionPolicyId, int retentionDays) {
        if (this.status == CohortStatus.CLOSED) {
            return; // 이미 종료된 기수 — 조용히 넘어간다(멱등)
        }
        OffsetDateTime now = OffsetDateTime.now();
        this.status = CohortStatus.CLOSED;
        this.closedAt = now;
        this.retentionPolicyId = retentionPolicyId;
        this.retentionUntil = now.plusDays(retentionDays);
        this.updatedBy = actorUserId;
    }

    /**
     * 이름·기간 부분 수정(11차 Q2). null인 값은 <b>바꾸지 않는다</b> — 반 수정과 같은 규칙이라
     * 이름만 고쳐도 기간이 덮이지 않는다.
     *
     * <p>개강 여부는 호출부가 판정한다. 엔티티는 값의 정합성(시작일 ≤ 종료일)만 지킨다.
     */
    public void edit(String newName, LocalDate newStartDate, LocalDate newEndDate, UUID actorUserId) {
        LocalDate resolvedStart = newStartDate == null ? this.startDate : newStartDate;
        LocalDate resolvedEnd = newEndDate == null ? this.endDate : newEndDate;
        if (resolvedStart != null && resolvedEnd != null && resolvedEnd.isBefore(resolvedStart)) {
            throw new IllegalArgumentException("종료일은 시작일보다 빠를 수 없습니다.");
        }
        if (newName != null) {
            this.name = newName;
        }
        this.startDate = resolvedStart;
        this.endDate = resolvedEnd;
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
