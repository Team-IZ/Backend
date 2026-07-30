package com.bigproject.backend.domain.project.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_requirement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRequirement {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "requirement_id", nullable = false, updatable = false)
    private UUID requirementId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    // NULL 허용. 최신 스키마에서 NOT NULL → NULL로 변경됨
    // "제목만으로 의미가 충분한 경우 상세 설명 생략 가능"
    @Column(name = "description")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private ProjectRequirement(UUID projectId, UUID orgId, Integer sequenceNo,
                               String title, String description, UUID createdBy) {
        this.projectId = projectId;
        this.orgId = orgId;
        this.sequenceNo = sequenceNo;
        this.title = title;
        this.description = description;
        this.active = true; // 새로 만든 요구사항은 항상 활성 상태로 시작
        this.createdBy = createdBy;
    }

    // 요구사항을 채점 대상에서 제외. 과거 판정 기록(project_requirement_assessment)이
    // 이 행을 FK로 참조하므로 삭제 대신 비활성화만 함
    public void deactivate() {
        this.active = false;
    }
}
