package projectexecution.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    // ===== 1묶음: 식별자 =====

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    // ===== 2묶음: 업무 필드 =====

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    // (cohort_id, sequence_no) 전체 UNIQUE — 삭제된 프로젝트의 번호도 재사용 불가.
    // 채번은 엔티티가 아니라 Service에서 max+1 조회 후 넘겨준다(동시성은 DB 유니크가 최종 방어선).
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private Integer sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "project_category", nullable = false, length = 30, updatable = false)
    private ProjectCategory projectCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "concept_source_mode", nullable = false, length = 30, updatable = false)
    private ConceptSourceMode conceptSourceMode;

    @Column(name = "curriculum_not_applicable", nullable = false, updatable = false)
    private boolean curriculumNotApplicable;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // ⚠ MINI=TOTAL / BIG=OWN_COMMIT 매핑은 기획 컨펌 전 잠정값. 확정되면 아래 매핑만 교체.
    @Enumerated(EnumType.STRING)
    @Column(name = "default_extraction_scope_code", nullable = false, length = 100, updatable = false)
    private ExtractionScope defaultExtractionScopeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 30)
    private ProjectLifecycleStatus lifecycleStatus;

    // ===== 3묶음: 감사 필드 =====

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    // ===== 4묶음: 생성과 행동 =====

    private Project(UUID orgId, UUID cohortId, String name, Integer sequenceNo,
                    ProjectCategory projectCategory, ConceptSourceMode conceptSourceMode,
                    boolean curriculumNotApplicable, LocalDate startDate, LocalDate endDate,
                    ExtractionScope defaultExtractionScopeCode, UUID createdBy) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        this.orgId = orgId;
        this.cohortId = cohortId;
        this.name = name;
        this.sequenceNo = sequenceNo;
        this.projectCategory = projectCategory;
        this.conceptSourceMode = conceptSourceMode;
        this.curriculumNotApplicable = curriculumNotApplicable;
        this.startDate = startDate;
        this.endDate = endDate;
        this.defaultExtractionScopeCode = defaultExtractionScopeCode;
        this.lifecycleStatus = ProjectLifecycleStatus.PLANNED;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 미니프로젝트 생성. concept_source_mode=PROJECT_FIXED, curriculum_not_applicable=false 고정.
     * 교안 연결 + 검증 개념 3건 확정이 이 프로젝트의 READY 조건이다(호출부 별도 처리).
     */
    public static Project createMiniProject(UUID orgId, UUID cohortId, String name, Integer sequenceNo,
                                            LocalDate startDate, LocalDate endDate, UUID createdBy) {
        return new Project(orgId, cohortId, name, sequenceNo,
                ProjectCategory.MINI_PROJECT, ConceptSourceMode.PROJECT_FIXED,
                false, startDate, endDate, ExtractionScope.TOTAL, createdBy);
    }

    /**
     * 빅프로젝트 생성. concept_source_mode=OWN_COMMIT_DYNAMIC, curriculum_not_applicable=true 고정.
     * 교안·검증 개념 세트를 만들지 않는다 — 만들려는 호출은 Service 단에서 막아야 한다.
     */
    public static Project createBigProject(UUID orgId, UUID cohortId, String name, Integer sequenceNo,
                                           LocalDate startDate, LocalDate endDate, UUID createdBy) {
        return new Project(orgId, cohortId, name, sequenceNo,
                ProjectCategory.BIG_PROJECT, ConceptSourceMode.OWN_COMMIT_DYNAMIC,
                true, startDate, endDate, ExtractionScope.OWN_COMMIT, createdBy);
    }

    /** 첫 회차가 열리면 진행 중으로 전환. PLANNED가 아니면 잘못된 호출이므로 막는다. */
    public void start(UUID actorUserId) {
        if (this.lifecycleStatus != ProjectLifecycleStatus.PLANNED) {
            throw new IllegalStateException("예정 상태의 프로젝트만 시작할 수 있습니다.");
        }
        this.lifecycleStatus = ProjectLifecycleStatus.RUNNING;
        this.updatedBy = actorUserId;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 프로젝트 종료. 이미 종료된 상태면 조용히 넘어간다(멱등) — 버튼 두 번 눌러도 안전. */
    public void close(UUID actorUserId) {
        if (this.lifecycleStatus == ProjectLifecycleStatus.CLOSED) {
            return;
        }
        this.lifecycleStatus = ProjectLifecycleStatus.CLOSED;
        this.updatedBy = actorUserId;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 일정 변경. CLOSED 상태에서는 막는다 — 종료된 회차의 마감을 되돌리는 것은
     * 이미 나간 판정 기준을 흔드는 일이라 허용하지 않는다.
     */
    public void updateSchedule(LocalDate startDate, LocalDate endDate, UUID actorUserId) {
        if (this.lifecycleStatus == ProjectLifecycleStatus.CLOSED) {
            throw new IllegalStateException("종료된 프로젝트의 일정은 변경할 수 없습니다.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        this.startDate = startDate;
        this.endDate = endDate;
        this.updatedBy = actorUserId;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 소프트 삭제. */
    public void softDelete(UUID actorUserId) {
        this.deletedAt = OffsetDateTime.now();
        this.updatedBy = actorUserId;
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}