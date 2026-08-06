package projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project_requirement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRequirement {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "requirement_id", nullable = false, updatable = false)
    private UUID requirementId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // ===== 2묶음: 업무 필드 =====

    // 같은 논리 요구사항의 버전을 묶는 안정 키. 프론트가 id를 안 주므로
    // (요구사항은 문자열 배열) title을 정규화해 서비스 계층에서 만들어 넣는다.
    @Column(name = "requirement_key", nullable = false, updatable = false, length = 100)
    private String requirementKey;

    @Column(name = "version_no", nullable = false, updatable = false)
    private Integer versionNo;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ===== 4묶음: 생성과 행동 =====

    @Builder
    private ProjectRequirement(UUID projectId, UUID orgId, String requirementKey,
                               Integer versionNo, Integer sequenceNo,
                               String title, String description, UUID createdBy) {
        OffsetDateTime now = OffsetDateTime.now();
        this.projectId = projectId;
        this.orgId = orgId;
        this.requirementKey = requirementKey;
        this.versionNo = versionNo;
        this.sequenceNo = sequenceNo;
        this.title = title;
        this.description = description;
        this.active = true;
        this.effectiveFrom = now;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 요구사항 최초 등록. version_no=1로 시작한다. */
    public static ProjectRequirement createFirstVersion(
            UUID projectId, UUID orgId, String requirementKey,
            int sequenceNo, String title, String description, UUID createdBy) {
        return ProjectRequirement.builder()
                .projectId(projectId)
                .orgId(orgId)
                .requirementKey(requirementKey)
                .versionNo(1)
                .sequenceNo(sequenceNo)
                .title(title)
                .description(description)
                .createdBy(createdBy)
                .build();
    }

    /**
     * 현재(활성) 버전을 종료한다. 판정 기록(ProjectRequirementAssessment)이
     * 이 버전을 참조할 수 있으므로 절대 title·description을 in-place 수정하지 않고,
     * 종료만 하고 새 버전은 createNextVersion()으로 별도 행을 만든다.
     */
    public void retire() {
        this.active = false;
        this.effectiveTo = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    /** 문구는 그대로이고 화면상 순서만 바뀐 경우. 새 버전이 아니다. */
    public void changeSequenceNo(int sequenceNo) {
        this.sequenceNo = sequenceNo;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 이 버전을 이어받는 다음 버전을 만든다. **호출 전에 반드시 retire()를 먼저 호출한다**
     * — 같은 트랜잭션 안에서 종료와 생성이 함께 일어나야 (project_id, requirement_key)
     * 활성 버전이 항상 최대 1건이라는 불변식이 깨지지 않는다.
     */
    public ProjectRequirement createNextVersion(String title, String description, UUID createdBy) {
        return ProjectRequirement.builder()
                .projectId(this.projectId)
                .orgId(this.orgId)
                .requirementKey(this.requirementKey)
                .versionNo(this.versionNo + 1)
                .sequenceNo(this.sequenceNo)
                .title(title)
                .description(description)
                .createdBy(createdBy)
                .build();
    }
}