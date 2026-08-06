package projectexecution.domain;

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

    public void supersede(OffsetDateTime effectiveTo) {
        if (this.status == ConceptSetStatus.SUPERSEDED) {
            return;
        }
        this.status = ConceptSetStatus.SUPERSEDED;
        this.effectiveTo = effectiveTo;
    }
}