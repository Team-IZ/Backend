package com.bigproject.backend.domain.classroom.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

//기수 안에 있는 class 정보를 담는 엔티티인데 DB 테이블 이름은 class인데 자바에서 class는 예약어라 쓸 수 없어서,
// 클래스 이름은 Classroom으로 하고 @Table로 실제 테이블과 연결

@Entity
@Table(name = "class")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Classroom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    // 어느 기관 소속인지. 다른 기관 데이터가 보이면 안 되니까 조회할 때 항상 이 값도 같이 확인합니다.
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // 어느 기수에 속한 반인지. Cohort 객체를 직접 들고 있으면 두 도메인이 서로 묶여버려서 ID 값만 저장합니다.
    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // 반을 삭제해도 데이터를 지우지 않고 삭제한 시각만 기록하고 값이 없으면 아직 살아있는 반
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Builder
    private Classroom(UUID orgId, UUID cohortId, String name, UUID createdBy) {
        this.orgId = orgId;
        this.cohortId = cohortId;
        this.name = name;
        this.createdBy = createdBy;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}