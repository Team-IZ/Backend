package com.bigproject.backend.domain.cohort.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cohort")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cohort {

    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cohort_id", nullable = false, updatable = false)
    private Long cohortId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "education_track", nullable = false, length = 100)
    private String educationTrack;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 100)
    private CohortStatus status;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private Cohort(Long orgId, String name, LocalDate startDate, LocalDate endDate,
                   String educationTrack, Long createdBy) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        this.orgId = orgId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.educationTrack = educationTrack;
        this.status = CohortStatus.PLANNED;
        this.createdBy = createdBy;
    }

    /** 기수 종료 */
    public void close(Long actorUserId) {
        this.status = CohortStatus.CLOSED;
        this.updatedBy = actorUserId;
    }

    /** 소프트 삭제 */
    public void softDelete(Long actorUserId) {
        this.deletedAt = OffsetDateTime.now();
        this.updatedBy = actorUserId;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}