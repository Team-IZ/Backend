package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;

import java.util.List;
import java.util.UUID;

public interface CurriculumService {

    List<CurriculumVersion> findLinkableCurricula(UUID orgId);

    /**
     * 연결 가능한 교안 + <b>분석 상태·항목 수</b>(18차 R2).
     *
     * <p>{@link #findLinkableCurricula}가 버전 엔티티만 주던 것을 대신한다. 화면이
     * {@code pageCount == null}로 분석 여부를 추측하고 있었는데, 그건 "쪽수를 아직 모른다"는
     * 뜻이지 "분석 중"이라는 뜻이 아니고 <b>분석 실패와도 구분되지 않는다.</b>
     *
     * <p>조회는 교안 수와 무관하게 고정 3건이다 — 버전 · 최신 분석 · 항목 수.
     */
    List<LinkableCurriculum> findLinkableCurriculaWithStatus(UUID orgId);

    /**
     * {@link #findLinkableCurriculaWithStatus}와 <b>같은 목록</b>이되 경로의 기수를 먼저 검증한다(22차 R7).
     *
     * <p>교안은 기관 단위라 결과가 기수에 따라 달라지지 않는다. 그래서 여태 경로의 {@code cohortId}를
     * 아예 쓰지 않았고, <b>존재하지 않는 기수를 넣어도 다른 기수와 똑같은 목록이 나갔다.</b>
     * 화면은 그 응답을 「이 기수에 교안이 있다」로 읽으므로, 지워진 기수를 가리키는 링크를 열어도
     * 아무 이상이 없는 것처럼 보였다.
     *
     * <p>목록을 기수로 좁히지는 않는다 — 그것은 사실이 아니다. 대신 <b>경로가 거짓말하지 않게</b>
     * 없는 기수면 {@code COHORT_NOT_FOUND}로 끊는다.
     */
    List<LinkableCurriculum> findLinkableCurriculaForCohort(UUID cohortId, UUID orgId);

    /**
     * 회차 생성 모달의 교안 한 줄.
     *
     * @param analysisStatus 가장 최근 분석 <b>시도</b>의 상태. 한 번도 분석하지 않았으면 {@code null}이다 —
     *                       실패({@code FAILED})와 구분해야 해서 값을 만들어 넣지 않는다
     * @param teachesCount   승인된(ACTIVE) 가르친 항목 수. 검증 개념 3건을 뽑을 수 있는지를
     *                       고르기 <b>전에</b> 알 수 있게 하는 값이다
     */
    record LinkableCurriculum(
            CurriculumVersion version,
            com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus analysisStatus,
            int teachesCount) {
    }

    /**
     * 이 기수의 회차들이 <b>실제로 연결한</b> 교안 목록(30차 Q2). 매니저 교안 화면이 쓰는 읽기 전용
     * 목록이며 등록·재분석 같은 쓰기 액션은 없다.
     *
     * <p>{@link #findLinkableCurriculaForCohort}와 이름이 비슷하지만 <b>정반대의 값</b>이다 —
     * 그쪽은 오퍼레이터가 회차에 <b>붙일 수 있는 후보</b>라 기관 전체이고, 이쪽은 이미 <b>붙어 있는
     * 것</b>이라 기수 범위다. 그 조회의 경로에 {@code cohortId}가 있는데 목록을 좁히지 않는 것이
     * 오해를 샀다.
     *
     * <p>한 교안이 여러 회차에 걸리면 <b>한 번만</b> 나오고 {@code linkedProjects}에 그 회차들이
     * 모두 담긴다. 그 목록이 없으면 기관 전체 목록과 구분되지 않는다 — 「이 교안이 3차에 쓰였다」가
     * 이 화면의 유일한 맥락이다.
     */
    List<LinkedCurriculum> findLinkedCurriculaForCohort(UUID cohortId, UUID orgId);

    /**
     * 기수에 연결된 교안 한 줄.
     *
     * @param linkedProjects 이 교안을 쓴 회차들이며 차수 오름차순이다. <b>비어 있지 않다</b> —
     *                       연결이 있어야 이 목록에 들어온다
     */
    record LinkedCurriculum(
            CurriculumVersion version,
            com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus analysisStatus,
            int teachesCount,
            List<ProjectService.CohortCurriculumLink> linkedProjects) {
    }

    CurriculumVersion getLinkableCurriculum(UUID versionId, UUID orgId);

    List<SectionItemView> findSectionItems(UUID sectionId, UUID orgId);

    List<SectionView> findSections(UUID versionId, UUID orgId);

    /**
     * 이 교안을 쓰는 회차들(11차 R3). 이름 배열이던 것을 회차 객체로 바꿨다 —
     * 화면이 재분석 경고를 <b>응시가 시작된 회차만</b>으로 좁힐 수 있어야 한다.
     *
     * <p>13차 R1 — 받는 값이 <b>교안 ID(materialId)</b>다. 예전에는 같은 자리를 교안 버전 ID로
     * 읽어서, 경로 이름대로 교안 ID를 넣으면 늘 빈 배열이었다. 교안 목록의
     * {@code usedProjectCount}·형제 엔드포인트 {@code /sections}와 같은 축으로 맞췄다.
     */
    List<ProjectService.CurriculumUsingProject> findUsedProjects(UUID materialId, UUID orgId);

    /**
     * GET /curricula/comparable-cohorts?cohortId= — 이 기수가 쓴 교안들과 겹치는 교안을 쓴
     * 다른 기수 목록. 기수 간 비교(OP-02) 화면에서 "비교 가능한 기수 후보"로 쓴다.
     */
    List<UUID> findComparableCohorts(UUID cohortId, UUID orgId);

    com.bigproject.backend.domain.curriculum.domain.CurriculumVersion registerCurriculum(
            UUID orgId, String title, String topic, org.springframework.web.multipart.MultipartFile file, UUID actorUserId);

    /**
     * 42차 R1 — 기존 교안(material)에 새 버전을 올린다. 새 material을 만들지 않는다.
     *
     * <p>새 버전은 그 material의 <b>현재 최신 버전 번호 + 1</b>로 번호가 매겨지고, 그 최신 버전은
     * {@link com.bigproject.backend.domain.curriculum.domain.CurriculumVersion#deactivate()}로
     * {@code INACTIVE}가 된다 — {@code findLinkableCurricula}류가 {@code ACTIVE}만 후보로 주므로,
     * 새 프로젝트를 연결할 때는 이제 이 새 버전만 골라진다. <b>기존 버전 행 자체와 그 버전에
     * 이미 연결된 프로젝트(project_curriculum)는 손대지 않는다</b> — 상태만 바뀔 뿐 불변성이
     * 깨지지 않는다.
     *
     * @param title null이거나 공백이면 material 제목을 그대로 둔다. 값이 있으면 제목을 바꿔 단다 —
     *              이때만 (기관 + 새 제목) 중복 검사를 한다(자기 자신과 같은 제목이면 통과)
     */
    com.bigproject.backend.domain.curriculum.domain.CurriculumVersion registerCurriculumVersion(
            UUID materialId, UUID orgId, String title,
            org.springframework.web.multipart.MultipartFile file, UUID actorUserId);

    /**
     * 교안 재분석 요청.
     *
     * @param force 진행 중({@code PENDING}·{@code RUNNING}) 검사를 건너뛴다(25차 R2).
     *              평소에는 {@code false}이고, 오래 멈춘 분석을 강제로 다시 돌릴 때만 켠다
     */
    void requestAnalysis(UUID materialId, UUID orgId, UUID actorUserId, boolean force);

    // ── 9차 R8: 기관 전체 교안 목록 · 단건 상세 ────────────────────────────────

    /**
     * 교안 탭이 보는 기관 전체 목록 한 페이지.
     *
     * @param statuses        분석 상태 필터. null·빈 목록이면 전체이고, 여러 값이면 합집합이다(25차 R1)
     * @param notAnalyzedOnly 한 번도 분석하지 않은 교안만(13차 R2). {@code statuses}와 함께 오면 400이다
     */
    CurriculumCatalogPage findCatalog(
            UUID orgId,
            String query,
            java.util.List<com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus> statuses,
            boolean notAnalyzedOnly,
            com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogSort sort,
            int page,
            int size);

    /**
     * 교안을 논리 삭제한다(25차 R11).
     *
     * <p>연결된 회차가 하나라도 있으면 지우지 않고 409({@code CURRICULUM_MATERIAL_IN_USE})다 —
     * 회차가 근거로 삼는 교안이 목록에서 사라지면 안 되기 때문이다. 연결을 먼저 끊는 경로는
     * {@code DELETE /projects/{projectId}/curricula/{id}}가 이미 있다.
     */
    void deleteCurriculum(UUID materialId, UUID orgId, UUID actorUserId);

    /** 교안 하나. 목록과 같은 조회를 쓰므로 필드가 어긋나지 않는다. */
    com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository.CurriculumCatalogRow
    findCatalogItem(UUID materialId, UUID orgId);

    /**
     * @param statusCounts 분석 상태별 교안 수(11차 R7). <b>필터를 적용하지 않은 기관 전체</b> 기준이라
     *                     {@code totalElements}와 다르다. 한 번도 분석하지 않은 교안은 키가 {@code null}이다
     */
    record CurriculumCatalogPage(
            List<com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository.CurriculumCatalogRow> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            java.util.Map<com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus, Long> statusCounts,
            long notAnalyzedCount) {
    }

    record SectionItemView(
            UUID mappingId,
            String extractedName,
            String description,
            boolean definitionMissing,
            Integer pageStart,
            Integer pageEnd,
            boolean usedAsVerificationConcept,
            List<String> usedRoundLabels
    ) {
    }

    record SectionView(
            UUID sectionId,
            String title,
            Integer pageStart,
            Integer pageEnd,
            List<SectionItemView> items
    ) {
    }
}