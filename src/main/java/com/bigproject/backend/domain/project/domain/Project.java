package com.bigproject.backend.domain.project.domain;

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
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 100)
    private ProjectType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_scope", nullable = false, length = 100)
    private TargetScope targetScope;

    // target_scope=CLASS일 때만 값이 있고, COHORT면 NULL
    @Column(name = "target_class_id")
    private UUID targetClassId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // 유형에서 파생: PERSONAL=TOTAL, TEAM=OWN_COMMIT
    @Enumerated(EnumType.STRING)
    @Column(name = "default_extraction_scope_code", nullable = false, length = 100)
    private ExtractionScope defaultExtractionScopeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 100)
    private ProjectStatus status;

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

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private Project(UUID orgId, UUID cohortId, String name, ProjectType type,
                    TargetScope targetScope, UUID targetClassId,
                    LocalDate startDate, LocalDate endDate, UUID createdBy) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        if (targetScope == TargetScope.CLASS && targetClassId == null) {
            throw new IllegalArgumentException("특정 반 프로젝트는 대상 반을 지정해야 합니다.");
        }
        if (targetScope == TargetScope.COHORT && targetClassId != null) {
            throw new IllegalArgumentException("기수 전체 프로젝트는 대상 반을 지정할 수 없습니다.");
        }
        this.orgId = orgId;
        this.cohortId = cohortId;
        this.name = name;
        this.type = type;
        this.targetScope = targetScope;
        this.targetClassId = targetClassId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = ProjectStatus.PLANNED;
        this.defaultExtractionScopeCode =
                (type == ProjectType.PERSONAL) ? ExtractionScope.TOTAL : ExtractionScope.OWN_COMMIT;
        this.createdBy = createdBy;
    }

    // 첫 checkpoint가 열리면 진행 중으로 전환. PLANNED가 아니면 잘못된 호출이므로 막는다.
    public void start(UUID actorUserId) {
        if (this.status != ProjectStatus.PLANNED) {
            throw new IllegalStateException("예정 상태의 프로젝트만 시작할 수 있습니다.");
        }
        this.status = ProjectStatus.RUNNING;
        this.updatedBy = actorUserId;
    }

    // 프로젝트 종료. 이미 종료된 상태면 조용히 넘어간다(멱등) — 버튼 두 번 눌러도 안전.
    public void close(UUID actorUserId) {
        if (this.status == ProjectStatus.CLOSED) {
            return;
        }
        this.status = ProjectStatus.CLOSED;
        this.updatedBy = actorUserId;
    }

    // 소프트 삭제
    public void softDelete(UUID actorUserId) {
        this.deletedAt = OffsetDateTime.now();
        this.updatedBy = actorUserId;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}