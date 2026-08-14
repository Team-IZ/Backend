package com.bigproject.backend.domain.projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * MG-08 팀. {@code status}는 DB CHECK(ck_team_status)로 {@code DRAFT}/{@code CONFIRMED} 둘뿐이다
 * (실제 DDL로 확인 완료) — 정의 문서의 5단계(편성 전·편성 중·전원 배정·확정·제출 시작)는
 * 이 두 값 + 미배정 인원 유무 + 제출 존재 유무를 조합해 화면/서비스가 계산한다.
 * 이 엔티티 자체는 DRAFT/CONFIRMED만 안다.
 */
@Entity
@Table(name = "team")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** 팀이 속한 반. DB가 NOT NULL로 강제 — 팀은 반을 섞지 않고 한 반 안에서만 구성된다. */
    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /** 화면 표시용 번호("1팀"). DB 컬럼이 text라 순번을 문자열로 저장한다. */
    @Column(name = "team_number", nullable = false, length = 50)
    private String teamNumber;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TeamStatus status;

    @Column(name = "min_member_count", nullable = false)
    private int minMemberCount;

    @Column(name = "max_member_count", nullable = false)
    private int maxMemberCount;

    /** 낙관적 잠금. ck_team_row_version(>= 0)에 맞춰 0에서 시작한다. */
    @Column(name = "row_version", nullable = false)
    private int rowVersion;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    private Team(UUID projectId, UUID classId, UUID orgId, String teamNumber, String name,
                 int minMemberCount, int maxMemberCount, UUID createdBy) {
        this.projectId = projectId;
        this.classId = classId;
        this.orgId = orgId;
        this.teamNumber = teamNumber;
        this.name = name;
        this.status = TeamStatus.DRAFT; // ck_team_status 기본값과 일치
        this.minMemberCount = minMemberCount;
        this.maxMemberCount = maxMemberCount;
        this.rowVersion = 0;
        this.createdBy = createdBy;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    /** 편성 확정. ④ 상태로 전이 — 아직 되돌릴 수 있다(reopen). */
    public void confirm() {
        this.status = TeamStatus.CONFIRMED;
    }

    /**
     * 편성 다시 열기. **제출이 시작된 뒤에는 호출하면 안 된다** — 이 메서드 자체는 그걸 막지 않으므로
     * 서비스(TeamService)가 "제출 존재 여부"를 먼저 확인해야 한다.
     */
    public void reopen() {
        this.status = TeamStatus.DRAFT;
    }

    public boolean isConfirmed() {
        return this.status == TeamStatus.CONFIRMED;
    }

    /** 소프트 삭제. status는 안 바꾼다 — ck_team_status가 DRAFT/CONFIRMED만 허용해 DISBANDED 값이 없다. */
    public void disband() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isDisbanded() {
        return this.deletedAt != null;
    }
}