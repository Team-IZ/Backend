package projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

// project(1) - measurement_plan(1, UNIQUE) - checkpoint(N) 구조의 중간 다리.
// "미프 3차" 같은 개별 회차는 checkpoint가 갖고, 이건 그 회차들을 묶는 계획 단위
@Entity
@Table(name = "measurement_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeasurementPlan {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    // UNIQUE — project 하나당 이 계획은 정확히 하나뿐
    @Column(name = "project_id", nullable = false, updatable = false, unique = true)
    private UUID projectId;

    // ===== 2묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ===== 3묶음: 생성 =====

    @Builder
    private MeasurementPlan(UUID orgId, UUID cohortId, UUID projectId, UUID createdBy) {
        this.orgId = orgId;
        this.cohortId = cohortId;
        this.projectId = projectId;
        this.createdBy = createdBy;
    }
}
