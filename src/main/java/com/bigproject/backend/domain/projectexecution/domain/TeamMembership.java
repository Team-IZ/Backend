package com.bigproject.backend.domain.projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "team_membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMembership {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "membership_id", nullable = false, updatable = false)
    private UUID membershipId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    // cohort_member가 아니라 project_membership을 참조한다.
    // "이 프로젝트 참여 자격이 있는 사람"만 팀 배정 대상이 되도록 하기 위함
    @Column(name = "project_membership_id", nullable = false, updatable = false)
    private UUID projectMembershipId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // ===== 2묶음: 업무 필드 =====

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_method", nullable = false, length = 100)
    private AssignmentMethod assignmentMethod;

    @Column(name = "from_at", nullable = false, updatable = false)
    private OffsetDateTime fromAt;

    @Column(name = "to_at")
    private OffsetDateTime toAt;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private TeamMembership(UUID teamId, UUID projectMembershipId, UUID orgId,
                           AssignmentMethod assignmentMethod, OffsetDateTime fromAt, UUID assignedBy) {
        this.teamId = teamId;
        this.projectMembershipId = projectMembershipId;
        this.orgId = orgId;
        this.assignmentMethod = assignmentMethod;
        this.fromAt = fromAt;
        this.assignedBy = assignedBy;
    }

    // 팀 배정 해제. 지우지 않고 종료 시각만 찍는다 — 과거에 이 팀 소속이었다는 사실이 남아야 한다
    public void unassign(OffsetDateTime toAt) {
        if (this.toAt != null) {
            return; // 멱등: 이미 해제된 배정을 다시 해제해도 안전하게 넘어감
        }
        if (toAt.isBefore(this.fromAt)) {
            throw new IllegalArgumentException("배정 해제 시각이 배정 시각보다 이릅니다.");
        }
        this.toAt = toAt;
    }

    // 구간은 [fromAt, toAt). 끝을 포함 안 시켜야 새 배정 시작 시각과 겹쳐도 한쪽만 유효해짐
    public boolean isEffectiveAt(OffsetDateTime at) {
        if (at.isBefore(this.fromAt)) {
            return false;
        }
        return this.toAt == null || at.isBefore(this.toAt);
    }
}
