package com.bigproject.backend.domain.project.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_requirement_assessment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRequirementAssessment {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Column(name = "requirement_id", nullable = false, updatable = false)
    private UUID requirementId;

    @Column(name = "checkpoint_id", nullable = false, updatable = false)
    private UUID checkpointId;

    // 팀 프로젝트면 teamId만, 개인 프로젝트면 userId만 값이 있다 (생성자에서 검증)
    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // ===== 2묶음: 업무 필드 =====

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 100)
    private AssessmentResult result;

    @Column(name = "note")
    private String note;

    @Column(name = "source_snapshot_id")
    private UUID sourceSnapshotId;

    @Column(name = "assessment_version", nullable = false)
    private Integer assessmentVersion;

    // 이전 판정을 대체하는 체인. 재판정이 일어나도 기존 행은 그대로 두고
    // 새 행이 이 값으로 이전 판정을 가리킨다 — "틀렸던 이력" 자체가 사라지면 안 되기 때문
    @Column(name = "supersedes_assessment_id", updatable = false)
    private UUID supersedesAssessmentId;

    @Column(name = "calculation_run_id")
    private String calculationRunId;

    @Column(name = "assessed_at")
    private OffsetDateTime assessedAt;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "assessed_by")
    private UUID assessedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // ===== 4묶음: 생성과 행동 =====

    private ProjectRequirementAssessment(UUID requirementId, UUID checkpointId, UUID teamId,
                                         UUID userId, UUID orgId, AssessmentResult result, String note, UUID sourceSnapshotId,
                                         Integer assessmentVersion, UUID supersedesAssessmentId, String calculationRunId,
                                         OffsetDateTime assessedAt, UUID assessedBy) {

        validateSubject(teamId, userId);
        validateResultConsistency(result, sourceSnapshotId, assessedAt);

        this.requirementId = requirementId;
        this.checkpointId = checkpointId;
        this.teamId = teamId;
        this.userId = userId;
        this.orgId = orgId;
        this.result = result;
        this.note = note;
        this.sourceSnapshotId = sourceSnapshotId;
        this.assessmentVersion = assessmentVersion;
        this.supersedesAssessmentId = supersedesAssessmentId;
        this.calculationRunId = calculationRunId;
        this.assessedAt = assessedAt;
        this.assessedBy = assessedBy;
    }

    // 최초 판정. 아직 채점 전이라 PENDING으로 시작하고 근거 없이 만들 수 있다
    public static ProjectRequirementAssessment createPending(UUID requirementId, UUID checkpointId,
                                                             UUID teamId, UUID userId, UUID orgId) {
        return new ProjectRequirementAssessment(requirementId, checkpointId, teamId, userId, orgId,
                AssessmentResult.PENDING, null, null, 1, null, null, null, null);
    }

    // 재판정. 기존 행을 고치지 않고 새 행을 만들어 이전 판정을 대체한다
    public ProjectRequirementAssessment supersede(AssessmentResult result, String note,
                                                  UUID sourceSnapshotId, String calculationRunId, OffsetDateTime assessedAt, UUID assessedBy) {
        return new ProjectRequirementAssessment(this.requirementId, this.checkpointId,
                this.teamId, this.userId, this.orgId, result, note, sourceSnapshotId,
                this.assessmentVersion + 1, this.assessmentId, calculationRunId, assessedAt, assessedBy);
    }

    // team_id/user_id 중 정확히 하나만 있어야 함. 둘 다 있거나 둘 다 없으면 잘못된 것
    private static void validateSubject(UUID teamId, UUID userId) {
        boolean hasTeam = teamId != null;
        boolean hasUser = userId != null;
        if (hasTeam == hasUser) {
            throw new IllegalArgumentException("team_id와 user_id 중 정확히 하나만 있어야 합니다.");
        }
    }

    // PENDING이 아니면 근거(스냅샷, 판정 시각)가 반드시 있어야 함
    private static void validateResultConsistency(AssessmentResult result, UUID sourceSnapshotId,
                                                  OffsetDateTime assessedAt) {
        if (result == AssessmentResult.PENDING) {
            return;
        }
        if (sourceSnapshotId == null || assessedAt == null) {
            throw new IllegalArgumentException(
                    "PENDING이 아닌 판정은 source_snapshot_id와 assessed_at이 모두 있어야 합니다.");
        }
    }

    public boolean isTeamAssessment() {
        return this.teamId != null;
    }
}
