package com.bigproject.backend.domain.project.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "team")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {
// ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 100)
    private TeamStatus status;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private Team(UUID projectId, UUID orgId, String name, UUID createdBy) {
        this.projectId = projectId;
        this.orgId = orgId;
        this.name = name;
        this.status = TeamStatus.ACTIVE; // 새로 만든 팀은 항상 활성 상태로 시작
        this.createdBy = createdBy;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    // 팀 해체. 물리 삭제 대신 상태 전환 — repository·requirement_assessment가 team_id를 FK로 참조한다.
    public void disband() {
        if (this.status == TeamStatus.DISBANDED) {
            return; // 멱등
        }
        this.status = TeamStatus.DISBANDED;
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isDisbanded() {
        return this.status == TeamStatus.DISBANDED;
    }
}
