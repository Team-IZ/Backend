package com.bigproject.backend.domain.projectexecution.domain;

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

// 화면의 "미프 3차" 그 자체. project_sequence_no/track_sequence_no는
// 동시성 문제 때문에 여기서 계산 안 하고 서비스 계층에서 채번해서 넣어준다
@Entity
@Table(name = "checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checkpoint {
    // ===== 1묶음: 식별자 =====
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "checkpoint_id", nullable = false, updatable = false)
    private UUID checkpointId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    // ===== 2묶음: 업무 필드 =====
    @Column(name = "project_sequence_no", nullable = false, updatable = false)
    private Integer projectSequenceNo;

    @Column(name = "track_sequence_no", nullable = false, updatable = false)
    private Integer trackSequenceNo;

    @Column(name = "measurement_date", nullable = false)
    private LocalDate measurementDate;

    @Column(name = "question_count", nullable = false)
    private Integer questionCount;

    @Column(name = "is_final", nullable = false)
    private boolean isFinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 100)
    private CheckpointStatus status;

    // ===== 3묶음: 감사 필드 =====
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

    // ===== 4묶음: 생성과 행동 =====
    @Builder
    private Checkpoint(UUID planId, Integer projectSequenceNo, Integer trackSequenceNo,
                       LocalDate measurementDate, Integer questionCount, boolean isFinal, UUID createdBy) {
        if (questionCount == null || questionCount <= 0) {
            throw new IllegalArgumentException("질문 수는 1 이상이어야 합니다.");
        }
        this.planId = planId;
        this.projectSequenceNo = projectSequenceNo;
        this.trackSequenceNo = trackSequenceNo;
        this.measurementDate = measurementDate;
        this.questionCount = questionCount;
        this.isFinal = isFinal;
        this.status = CheckpointStatus.PLANNED;
        this.createdBy = createdBy;
    }

    public void open() {
        if (this.status != CheckpointStatus.PLANNED) {
            throw new IllegalStateException("예정 상태의 회차만 열 수 있습니다.");
        }
        this.status = CheckpointStatus.OPEN;
    }

    public void close() {
        if (this.status == CheckpointStatus.CLOSED) {
            return;
        }
        if (this.status != CheckpointStatus.OPEN) {
            throw new IllegalStateException("응시 가능 상태의 회차만 마감할 수 있습니다.");
        }
        this.status = CheckpointStatus.CLOSED;
    }

    public void complete() {
        this.status = CheckpointStatus.COMPLETED;
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }
}