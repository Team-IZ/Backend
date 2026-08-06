package projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "project_verification_concept")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectVerificationConcept {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_concept_id", nullable = false, updatable = false)
    private UUID projectConceptId;

    @Column(name = "concept_set_id", nullable = false, updatable = false)
    private UUID conceptSetId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "teaches_id", nullable = false, updatable = false)
    private UUID teachesId;

    @Column(name = "source_mapping_id", nullable = false, updatable = false)
    private UUID sourceMappingId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private Integer sequenceNo;

    private ProjectVerificationConcept(UUID conceptSetId, UUID orgId, UUID teachesId, UUID sourceMappingId, Integer sequenceNo) {
        this.conceptSetId = conceptSetId;
        this.orgId = orgId;
        this.teachesId = teachesId;
        this.sourceMappingId = sourceMappingId;
        this.sequenceNo = sequenceNo;
    }

    public static ProjectVerificationConcept of(UUID conceptSetId, UUID orgId, UUID teachesId, UUID sourceMappingId, Integer sequenceNo) {
        return new ProjectVerificationConcept(conceptSetId, orgId, teachesId, sourceMappingId, sequenceNo);
    }
}