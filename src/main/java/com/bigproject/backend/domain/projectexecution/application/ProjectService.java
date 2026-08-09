package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectListSort;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
import com.bigproject.backend.domain.projectexecution.domain.ProjectRequirement;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ProjectService {

    void markRunning(UUID projectId, UUID orgId, UUID actorUserId);

    List<ProjectRequirement> replaceRequirements(
            UUID projectId, UUID orgId, List<String> requirementTitles, UUID actorUserId);

    String resolveMiniProjectRoundLabel(UUID projectId, UUID orgId);

    List<String> findRoundLabelsUsingTeaches(UUID teachesId, UUID orgId);

    List<String> findRoundLabelsUsingCurriculum(UUID curriculumVersionId, UUID orgId);

    Project createProject(UUID orgId, UUID cohortId, String name, ProjectCategory category,
                          LocalDate startDate, LocalDate endDate, UUID actorUserId);

    List<Project> findProjects(UUID cohortId, UUID orgId);

    Project findProject(UUID projectId, UUID orgId);

    Project updateSchedule(UUID projectId, UUID orgId, LocalDate startDate, LocalDate endDate, UUID actorUserId);

    List<ConceptCandidate> findConceptCandidates(UUID projectId, UUID orgId);

    void confirmConcepts(UUID projectId, UUID orgId, List<UUID> mappingIds, UUID actorUserId);

    /**
     * 검증개념 후보 하나. {@code CurriculumService.SectionItemView}(교안 섹션 항목)와 <b>같은 원장</b>
     * (curriculum_teaches_mapping)에서 나오므로 같은 값들을 담는다 — 후보를 고르는 화면이 교안·섹션별로
     * 묶어 그리는데, 좁은 쪽에 맞춰 두면 후보가 평평한 한 덩어리가 되어 `p.55`가 어느 교안의 55쪽인지
     * 알 수 없다(9차 R2).
     *
     * @param description       정의문. {@code definitionMissing}이 true면 null이다
     * @param curriculumVersionId 이 후보가 나온 교안 버전 — 후보를 묶는 기준
     * @param sectionTitle      섹션 제목. 섹션 정보가 없는 매핑이면 null
     */
    record ConceptCandidate(
            UUID mappingId,
            UUID teachesId,
            String extractedName,
            String description,
            boolean definitionMissing,
            UUID curriculumVersionId,
            UUID sectionId,
            String sectionTitle,
            Integer pageStart,
            Integer pageEnd) {
    }

    // ── 9차 R1: 저장한 것을 되읽는 조회 ──────────────────────────────────────────

    /**
     * 프로젝트 상세 한 건. 상세 화면(개요·구성·현황 탭)이 한 번에 필요로 하는 값을 한 응답에 담는다 —
     * 교안·개념·요구사항을 따로 부르면 화면 진입에 조회가 4건이 된다(9차 R1).
     */
    ProjectDetail findProjectDetail(UUID projectId, UUID orgId);

    /** 목록 화면용. 항목마다 교안 수·개념 수·후보 수를 함께 센다. */
    List<ProjectSummary> findProjectSummaries(UUID cohortId, UUID orgId);

    // ── 9차 R3: 검색·필터·정렬·상태별 개수 ────────────────────────────────────

    /** 목록 한 벌 + 필터와 무관한 상태별 개수. */
    ProjectList findProjectList(UUID cohortId, UUID orgId, ProjectListCriteria criteria);

    /**
     * @param search        회차 이름 부분검색(대소문자 무시). null·공백이면 전체
     * @param curriculumId  교안으로 좁힌다. <b>교안 버전 ID와 자료(material) ID를 모두 받는다</b> —
     *                      화면이 어느 쪽을 들고 있든 되도록. null이면 전체
     * @param status        상태로 좁힌다. null이면 전체
     * @param sort          null이면 {@link ProjectListSort#READINESS}
     */
    record ProjectListCriteria(
            String search,
            UUID curriculumId,
            ProjectLifecycleStatus status,
            ProjectListSort sort) {
    }

    /**
     * @param projects 필터·정렬이 적용된 목록
     * @param counts   <b>필터를 적용하지 않은</b> 기수 전체 모집단의 상태별 개수.
     *                 상태 칩이 자기 자신을 필터링하면 안 되므로 걸러진 목록에서는 만들 수 없다
     */
    record ProjectList(
            List<ProjectSummary> projects,
            Map<ProjectLifecycleStatus, Long> counts) {
    }

    /** 생성·수정 응답처럼 이미 손에 든 프로젝트 하나를 목록 항목과 같은 모양으로 만든다. */
    ProjectSummary summarize(Project project, UUID orgId);

    /**
     * 프로젝트에 연결된 교안 한 건.
     *
     * @param materialId       교안(자료) ID. 버전이 바뀌어도 유지되는 안정 식별자라 화면 이동에 쓴다
     * @param originalFileName 업로드된 파일명. 화면이 `spring_backend_v1.pdf`로 표시한다
     */
    record LinkedCurriculum(
            UUID projectCurriculumId,
            UUID curriculumVersionId,
            UUID materialId,
            String originalFileName,
            Integer versionNo,
            OffsetDateTime linkedAt) {
    }

    /**
     * 확정된 검증 개념 한 건. {@code curriculumVersionId}·{@code pageStart}·{@code pageEnd}는
     * <b>확정 뒤에도</b> 계속 쓰인다 — 구성 탭이 개념마다 `· spring_backend_v1 v1 · p.53`을 쓰고,
     * 리포트와 면담 브리프가 그 값으로 교안 위치를 가리킨다.
     */
    record ConfirmedConcept(
            UUID mappingId,
            UUID teachesId,
            String extractedName,
            UUID curriculumVersionId,
            Integer pageStart,
            Integer pageEnd) {
    }

    /**
     * 프로젝트 + 목록 화면이 셀을 그리는 데 필요한 세 숫자.
     *
     * <p>준비 상태 판정({@link #readiness()})을 여기 둔 이유는 <b>정렬과 표시가 같은 규칙을 써야</b>
     * 하기 때문이다(9차 Q1·R3). 규칙이 두 곳에 있으면 목록의 순서와 배지가 서로 다른 말을 한다.
     */
    record ProjectSummary(
            Project project,
            int curriculumCount,
            int conceptCount,
            int conceptCandidateCount) {

        /**
         * 준비가 덜 된 정도 — 교안·확정 개념·마감 셋 중 <b>비어 있는 개수</b>다.
         * {@link ProjectListSort#READINESS} 정렬이 이 값의 내림차순을 쓴다.
         */
        public int unreadyCount() {
            int count = 0;
            if (curriculumCount == 0) {
                count++;
            }
            if (conceptCount == 0) {
                count++;
            }
            if (project.getEndDate() == null) {
                count++;
            }
            return count;
        }

        /** 하나라도 비어 있으면 {@code PREP}, 셋 다 찼으면 {@code READY}. */
        public ProjectReadiness readiness() {
            return unreadyCount() == 0 ? ProjectReadiness.READY : ProjectReadiness.PREP;
        }
    }

    /** {@link ProjectSummary}에 상세 화면이 쓰는 세 목록을 얹은 것. */
    record ProjectDetail(
            ProjectSummary summary,
            List<LinkedCurriculum> curricula,
            List<ConfirmedConcept> concepts,
            List<String> requirementTitles) {
    }

    /** 이 기수(cohortId) 소속 프로젝트들이 연결한 curriculum_version_id 전체(중복 제거). */
    List<UUID> findLinkedCurriculumVersionIds(UUID cohortId, UUID orgId);

    /** 주어진 curriculum_version_id들 중 하나라도 연결한 다른 기수들의 cohortId(자기 자신 제외, 중복 제거). */
    List<UUID> findCohortsSharingAnyCurriculum(List<UUID> curriculumVersionIds, UUID excludeCohortId, UUID orgId);

    ProjectCurriculum linkCurriculum(UUID projectId, UUID orgId, UUID curriculumVersionId, UUID actorUserId);

    // ── 9차 R4: 삭제 ────────────────────────────────────────────────────────

    /** 교안 연결 해제. 확정된 검증 개념이 그 교안을 쓰고 있으면 409로 막는다. */
    void unlinkCurriculum(UUID projectId, UUID orgId, UUID projectCurriculumId, UUID actorUserId);

    /** 회차 삭제(소프트). 제출·응시가 붙어 있으면 409로 막는다. */
    void deleteProject(UUID projectId, UUID orgId, UUID actorUserId);
}