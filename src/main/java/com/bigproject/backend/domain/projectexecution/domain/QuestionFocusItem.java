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

// 질문 생성 시 고를 수 있는 초점 항목 목록 (예: DB/데이터 설계, 인증·보안, API 설계 등).
// 매니저가 평가 포커스 인풋에서 고르는 그 목록이 여기서 나온다
@Entity
@Table(name = "question_focus_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionFocusItem {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "question_focus_item_id", nullable = false, updatable = false)
    private UUID questionFocusItemId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "taxonomy_version", nullable = false)
    private Integer taxonomyVersion;

    // ===== 3묶음: 감사 필드 =====

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private QuestionFocusItem(String name, String description, Integer taxonomyVersion) {
        if (taxonomyVersion == null || taxonomyVersion <= 0) {
            throw new IllegalArgumentException("분류체계 버전은 1 이상이어야 합니다.");
        }
        this.name = name;
        this.description = description;
        this.active = true; // 새로 등록한 항목은 바로 사용 가능
        this.taxonomyVersion = taxonomyVersion;
    }

    public void deactivate() {
        this.active = false;
    }
}