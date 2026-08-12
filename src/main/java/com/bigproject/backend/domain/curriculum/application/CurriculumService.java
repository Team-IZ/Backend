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

    void requestAnalysis(UUID materialId, UUID orgId, UUID actorUserId);

    // ── 9차 R8: 기관 전체 교안 목록 · 단건 상세 ────────────────────────────────

    /**
     * 교안 탭이 보는 기관 전체 목록 한 페이지.
     *
     * @param notAnalyzedOnly 한 번도 분석하지 않은 교안만(13차 R2). {@code status}와 함께 오면 400이다
     */
    CurriculumCatalogPage findCatalog(
            UUID orgId,
            String query,
            com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus status,
            boolean notAnalyzedOnly,
            com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogSort sort,
            int page,
            int size);

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