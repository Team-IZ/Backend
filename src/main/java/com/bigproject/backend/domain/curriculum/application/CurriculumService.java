package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;

import java.util.List;
import java.util.UUID;

public interface CurriculumService {

    List<CurriculumVersion> findLinkableCurricula(UUID orgId);

    CurriculumVersion getLinkableCurriculum(UUID versionId, UUID orgId);

    List<SectionItemView> findSectionItems(UUID sectionId, UUID orgId);

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
}