package projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_curriculum")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectCurriculum {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_curriculum_id", nullable = false, updatable = false)
    private UUID projectCurriculumId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "curriculum_version_id", nullable = false, updatable = false)
    private UUID curriculumVersionId;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "linked_by", nullable = false, updatable = false)
    private UUID linkedBy;

    private ProjectCurriculum(UUID orgId, UUID projectId, UUID curriculumVersionId, Integer sequenceNo, UUID linkedBy) {
        this.orgId = orgId;
        this.projectId = projectId;
        this.curriculumVersionId = curriculumVersionId;
        this.sequenceNo = sequenceNo;
        this.linkedAt = OffsetDateTime.now();
        this.linkedBy = linkedBy;
    }

    public static ProjectCurriculum link(UUID orgId, UUID projectId, UUID curriculumVersionId, Integer sequenceNo, UUID linkedBy) {
        return new ProjectCurriculum(orgId, projectId, curriculumVersionId, sequenceNo, linkedBy);
    }
}