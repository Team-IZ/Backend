package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial;
import com.bigproject.backend.domain.curriculum.domain.CurriculumSection;
import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersionStatus;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private final CurriculumMaterialRepository materialRepository;
    private final FileStorageService fileStorageService;
    private final AiCurriculumClient aiCurriculumClient;

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
    @Transactional
    public CurriculumVersion registerCurriculum(UUID orgId, String title, String topic, MultipartFile file, UUID actorUserId) {
        if (file == null || file.isEmpty()) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_UNAVAILABLE, "업로드할 파일이 없습니다.");
        }

        String normalizedTitle = title.trim().replaceAll("\\s+", " ").toLowerCase();

        CurriculumMaterial material = CurriculumMaterial.create(orgId, title, normalizedTitle, topic, "PDF", actorUserId);
        materialRepository.save(material);

        FileStorageService.StoredFile stored = fileStorageService.store(file);

        CurriculumVersion version = CurriculumVersion.createFirstVersion(
                material.getMaterialId(), stored.originalFileName(), stored.fileUri(),
                stored.fileSizeBytes(), stored.contentHash(), actorUserId);

        return curriculumVersionRepository.save(version);
    }

    @Override
    @Transactional
    public void requestAnalysis(UUID materialId, UUID orgId, UUID actorUserId) {
        if (!materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND);
        }

        CurriculumVersion version = curriculumVersionRepository
                .findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId)
                .stream().findFirst()
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

        byte[] pdfBytes;
        try {
            pdfBytes = Files.readAllBytes(Paths.get(URI.create(version.getFileUri())));
        } catch (IOException e) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_UNAVAILABLE, "저장된 파일을 읽을 수 없습니다.");
        }

        String idempotencyKey = version.getVersionId() + ":1";
        aiCurriculumClient.requestAnalysis(version.getVersionId(), "AI", pdfBytes, idempotencyKey);

        UUID placeholderModelId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        CurriculumAnalysis analysis = CurriculumAnalysis.createInitial(
                version.getVersionId(), placeholderModelId, 1,
                UUID.randomUUID(), idempotencyKey, actorUserId);

        analysisRepository.save(analysis);
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