package com.bigproject.backend.domain.project.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// checkpoint 하나에 붙는 질문 초점 이력. 회차 하나가 검증 개념 3건을 고정으로 갖는데,
// 그 각각이 이 테이블의 행 하나씩이다. ProjectExtractionScope와 같은 기간형 오버라이드 패턴
@Entity
@Table(name = "checkpoint_question_focus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckpointQuestionFocus {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "focus_id", nullable = false, updatable = false)
    private UUID focusId;

    @Column(name = "checkpoint_id", nullable = false, updatable = false)
    private UUID checkpointId;

    @Column(name = "question_focus_item_id", nullable = false, updatable = false)
    private UUID questionFocusItemId;

    // ===== 2묶음: 업무 필드 =====

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
    private CheckpointQuestionFocus(UUID checkpointId, UUID questionFocusItemId,
                                    Integer versionNo, OffsetDateTime effectiveFrom, UUID changedBy) {
        this.checkpointId = checkpointId;
        this.questionFocusItemId = questionFocusItemId;
        this.versionNo = versionNo;
        this.effectiveFrom = effectiveFrom;
        this.changedBy = changedBy;
    }

    // ProjectExtractionScope.expire와 동일 패턴 — 지우지 않고 종료 시각만 찍는다
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
