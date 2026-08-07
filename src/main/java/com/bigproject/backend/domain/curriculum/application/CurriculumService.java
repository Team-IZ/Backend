package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;

import java.util.List;
import java.util.UUID;

public interface CurriculumService {

    List<CurriculumVersion> findLinkableCurricula(UUID orgId);

    CurriculumVersion getLinkableCurriculum(UUID versionId, UUID orgId);

    List<SectionItemView> findSectionItems(UUID sectionId, UUID orgId);

    List<SectionView> findSections(UUID versionId, UUID orgId);

    List<String> findUsedProjects(UUID versionId, UUID orgId);

    /**
     * GET /curricula/comparable-cohorts?cohortId= — 이 기수가 쓴 교안들과 겹치는 교안을 쓴
     * 다른 기수 목록. 기수 간 비교(OP-02) 화면에서 "비교 가능한 기수 후보"로 쓴다.
     */
    List<UUID> findComparableCohorts(UUID cohortId, UUID orgId);

    com.bigproject.backend.domain.curriculum.domain.CurriculumVersion registerCurriculum(
            UUID orgId, String title, String topic, org.springframework.web.multipart.MultipartFile file, UUID actorUserId);

    void requestAnalysis(UUID materialId, UUID orgId, UUID actorUserId);

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