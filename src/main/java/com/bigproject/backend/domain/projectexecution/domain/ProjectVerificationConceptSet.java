package com.bigproject.backend.domain.projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_verification_concept_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectVerificationConceptSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "concept_set_id", nullable = false, updatable = false)
    private UUID conceptSetId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ConceptSetStatus status;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "change_reason")
    private String changeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private ProjectVerificationConceptSet(UUID projectId, UUID orgId, Integer versionNo, UUID createdBy, String changeReason) {
        OffsetDateTime now = OffsetDateTime.now();
        this.projectId = projectId;
        this.orgId = orgId;
        this.versionNo = versionNo;
        this.status = ConceptSetStatus.ACTIVE;
        this.effectiveFrom = now;
        this.createdBy = createdBy;
        this.changeReason = changeReason;
        this.createdAt = now;
    }

    public static ProjectVerificationConceptSet activate(UUID projectId, UUID orgId, Integer versionNo, UUID createdBy, String changeReason) {
        return new ProjectVerificationConceptSet(projectId, orgId, versionNo, createdBy, changeReason);
    }

    /** {@code effective_to > effective_from} 을 만족시키기 위한 최소 간격. TIMESTAMPTZ 의 분해능이 마이크로초다. */
    private static final long MINIMUM_INTERVAL_NANOS = 1_000L;

    /**
     * 이 세트를 닫는다.
     *
     * <p>닫는 시각이 {@code effectiveFrom} 보다 뒤가 아니면 그 바로 다음 순간으로 당겨 쓴다.
     * DB 가 {@code ck_proj_verif_concept_set_effective_to
     * CHECK (effective_to IS NULL OR effective_to > effective_from)} 로 <b>같은 시각도 막기</b> 때문이다.
     * 두 경우에 실제로 걸린다 — ① 확정 직후 같은 순간에 다시 확정할 때,
     * ② {@code effectiveFrom} 이 미래인 세트를 닫을 때(시드 데이터에 그런 행이 있다).
     * 한 번도 유효한 적 없던 세트라는 뜻이므로 길이 0에 가장 가까운 구간으로 남긴다.
     */
    public void supersede(OffsetDateTime effectiveTo) {
        if (this.status == ConceptSetStatus.SUPERSEDED) {
            return;
        }
        this.status = ConceptSetStatus.SUPERSEDED;
        this.effectiveTo = effectiveTo.isAfter(this.effectiveFrom)
                ? effectiveTo
                : this.effectiveFrom.plusNanos(MINIMUM_INTERVAL_NANOS);
    }
}