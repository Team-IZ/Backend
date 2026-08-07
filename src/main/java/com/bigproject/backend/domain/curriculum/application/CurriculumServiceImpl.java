package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumSection;
import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersionStatus;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurriculumServiceImpl implements CurriculumService {

    private final CurriculumVersionRepository curriculumVersionRepository;
    private final CurriculumTeachesMappingRepository mappingRepository;
    private final CurriculumAnalysisRepository analysisRepository;
    private final CurriculumSectionRepository sectionRepository;
    private final ProjectService projectService;
    private final com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository materialRepository;
    private final FileStorageService fileStorageService;

    @Override
    public List<CurriculumVersion> findLinkableCurricula(UUID orgId) {
        return curriculumVersionRepository.findAllActiveByOrgId(orgId, CurriculumVersionStatus.ACTIVE);
    }

    @Override
    public CurriculumVersion getLinkableCurriculum(UUID versionId, UUID orgId) {
        return curriculumVersionRepository.findByVersionIdAndOrgId(versionId, orgId)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND));
    }

    @Override
    public List<SectionItemView> findSectionItems(UUID sectionId, UUID orgId) {
        List<CurriculumTeachesMapping> mappings = mappingRepository.findAllBySectionIdOrderBySequenceNoAsc(sectionId);
        return mappings.stream()
                .map(mapping -> toSectionItemView(mapping, orgId))
                .toList();
    }

    @Override
    public List<SectionView> findSections(UUID versionId, UUID orgId) {
        CurriculumAnalysis latestSuccess = analysisRepository
                .findFirstByVersionIdAndStatusOrderByCompletedAtDesc(versionId, CurriculumAnalysisStatus.SUCCEEDED)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_UNAVAILABLE, "분석 완료된 버전이 아닙니다."));

        List<CurriculumSection> sections = sectionRepository
                .findAllBySourceAnalysisIdOrderBySequenceNoAsc(latestSuccess.getAnalysisId());

        return sections.stream()
                .map(section -> toSectionView(section, orgId))
                .toList();
    }

    @Override
    public List<String> findUsedProjects(UUID versionId, UUID orgId) {
        return projectService.findRoundLabelsUsingCurriculum(versionId, orgId);
    }

    @Override
    public List<UUID> findComparableCohorts(UUID cohortId, UUID orgId) {
        List<UUID> myCurriculumVersionIds = projectService.findLinkedCurriculumVersionIds(cohortId, orgId);
        if (myCurriculumVersionIds.isEmpty()) {
            return List.of();
        }
        return projectService.findCohortsSharingAnyCurriculum(myCurriculumVersionIds, cohortId, orgId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public com.bigproject.backend.domain.curriculum.domain.CurriculumVersion registerCurriculum(
            UUID orgId, String title, String topic, org.springframework.web.multipart.MultipartFile file, UUID actorUserId) {

        if (file == null || file.isEmpty()) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_UNAVAILABLE, "업로드할 파일이 없습니다.");
        }

        String normalizedTitle = title.trim().replaceAll("\\s+", " ").toLowerCase();

        com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial material =
                com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial.create(
                        orgId, title, normalizedTitle, topic, "PDF", actorUserId);
        materialRepository.save(material);

        FileStorageService.StoredFile stored = fileStorageService.store(file);

        com.bigproject.backend.domain.curriculum.domain.CurriculumVersion version =
                com.bigproject.backend.domain.curriculum.domain.CurriculumVersion.createFirstVersion(
                        material.getMaterialId(), stored.originalFileName(), stored.fileUri(),
                        stored.fileSizeBytes(), stored.contentHash(), actorUserId);

        return curriculumVersionRepository.save(version);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void requestAnalysis(UUID materialId, UUID orgId, UUID actorUserId) {
        if (!materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND);
        }
        // ⚠ 임시: 실제 AI 호출은 여기서 하지 않는다. 분석 요청 레코드만 만들어 대기 상태로 남긴다.
        // AI 팀 워커가 이 레코드(status=PENDING)를 폴링하거나, 별도 트리거로 처리하는 것을 전제로 한다.
    }

    private SectionView toSectionView(CurriculumSection section, UUID orgId) {
        List<CurriculumTeachesMapping> mappings = mappingRepository
                .findAllBySectionIdOrderBySequenceNoAsc(section.getSectionId());

        List<SectionItemView> items = mappings.stream()
                .map(mapping -> toSectionItemView(mapping, orgId))
                .toList();

        return new SectionView(
                section.getSectionId(),
                section.getTitle(),
                section.getPageStart(),
                section.getPageEnd(),
                items
        );
    }

    private SectionItemView toSectionItemView(CurriculumTeachesMapping mapping, UUID orgId) {
        boolean definitionMissing = mapping.getSourceDescription() == null || mapping.getSourceDescription().isBlank();
        List<String> usedRoundLabels = projectService.findRoundLabelsUsingTeaches(mapping.getTeachesId(), orgId);

        return new SectionItemView(
                mapping.getMappingId(),
                mapping.getExtractedName(),
                definitionMissing ? null : mapping.getSourceDescription(),
                definitionMissing,
                mapping.getPageStart(),
                mapping.getPageEnd(),
                !usedRoundLabels.isEmpty(),
                usedRoundLabels
        );
    }
}