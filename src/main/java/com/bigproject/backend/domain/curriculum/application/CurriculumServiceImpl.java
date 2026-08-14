package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogSort;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial;
import com.bigproject.backend.domain.curriculum.domain.CurriculumSection;
import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersionStatus;
import com.bigproject.backend.domain.curriculum.domain.MappingStatus;
import com.bigproject.backend.domain.curriculum.domain.Teaches;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.TeachesRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import com.bigproject.backend.global.exception.ApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurriculumServiceImpl implements CurriculumService {

    // ✅ 원본 방식 복구: 별도 설정 파일 없이 여기서 직접 S3Client 생성
    private static final S3Client S3_CLIENT = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    private static final int POLL_MAX_ATTEMPTS = 100;
    private static final long POLL_INTERVAL_MS = 3000L;
    private static final java.time.Duration MAX_WAIT_DURATION = java.time.Duration.ofMinutes(25);

    private final CurriculumVersionRepository curriculumVersionRepository;
    private final CurriculumTeachesMappingRepository mappingRepository;
    private final CurriculumAnalysisRepository analysisRepository;
    private final CurriculumSectionRepository sectionRepository;
    private final ProjectService projectService;
    private final CurriculumMaterialRepository materialRepository;
    private final FileStorageService fileStorageService;
    private final AiCurriculumClient aiCurriculumClient;
    private final CurriculumCatalogRepository catalogRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TeachesRepository teachesRepository;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private CurriculumServiceImpl self;

    @Override
    public List<CurriculumVersion> findLinkableCurricula(UUID orgId) {
        return curriculumVersionRepository.findAllActiveByOrgId(orgId, CurriculumVersionStatus.ACTIVE);
    }

    @Override
    public List<LinkableCurriculum> findLinkableCurriculaForCohort(UUID cohortId, UUID orgId) {
        if (!catalogRepository.cohortExists(cohortId, orgId)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
        }
        return findLinkableCurriculaWithStatus(orgId);
    }

    @Override
    public List<LinkableCurriculum> findLinkableCurriculaWithStatus(UUID orgId) {
        List<CurriculumVersion> versions = findLinkableCurricula(orgId);
        if (versions.isEmpty()) {
            return List.of();
        }
        List<UUID> versionIds = versions.stream().map(CurriculumVersion::getVersionId).toList();

        Map<UUID, CurriculumAnalysisStatus> statusByVersion = new HashMap<>();
        for (CurriculumAnalysis analysis : analysisRepository
                .findAllByVersionIdInOrderByRequestedAtDesc(versionIds)) {
            statusByVersion.putIfAbsent(analysis.getVersionId(), analysis.getStatus());
        }

        Map<UUID, Long> teachesByVersion = new HashMap<>();
        for (Object[] row : mappingRepository.countActiveCandidatesByVersionIds(
                versionIds, orgId, MappingStatus.ACTIVE)) {
            teachesByVersion.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        return versions.stream()
                .map(version -> new LinkableCurriculum(
                        version,
                        statusByVersion.get(version.getVersionId()),
                        Math.toIntExact(teachesByVersion.getOrDefault(version.getVersionId(), 0L))))
                .toList();
    }

    @Override
    public CurriculumVersion getLinkableCurriculum(UUID versionId, UUID orgId) {
        return curriculumVersionRepository.findByVersionIdAndOrgId(versionId, orgId)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND));
    }

    @Override
    public List<SectionItemView> findSectionItems(UUID sectionId, UUID orgId) {
        List<CurriculumTeachesMapping> mappings = mappingRepository.findAllBySectionIdOrderBySequenceNoAsc(sectionId);
        return toSectionItemViews(mappings, orgId);
    }

    @Override
    public List<SectionView> findSections(UUID versionId, UUID orgId) {
        CurriculumAnalysis latestSuccess = analysisRepository
                .findFirstByVersionIdAndStatusOrderByCompletedAtDesc(versionId, CurriculumAnalysisStatus.SUCCEEDED)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_ANALYSIS_NOT_COMPLETED));

        List<CurriculumSection> sections = sectionRepository
                .findAllBySourceAnalysisIdOrderBySequenceNoAsc(latestSuccess.getAnalysisId());
        if (sections.isEmpty()) {
            return List.of();
        }

        List<UUID> sectionIds = sections.stream().map(CurriculumSection::getSectionId).toList();
        Map<UUID, List<CurriculumTeachesMapping>> mappingsBySectionId = mappingRepository
                .findAllBySectionIdInOrderBySequenceNoAsc(sectionIds).stream()
                .collect(Collectors.groupingBy(CurriculumTeachesMapping::getSectionId,
                        LinkedHashMap::new, Collectors.toList()));

        Map<UUID, List<String>> roundLabelsByTeachesId = projectService.findRoundLabelsByTeaches(
                mappingsBySectionId.values().stream()
                        .flatMap(List::stream)
                        .map(CurriculumTeachesMapping::getTeachesId)
                        .collect(Collectors.toSet()),
                orgId);

        return sections.stream()
                .map(section -> new SectionView(
                        section.getSectionId(),
                        section.getTitle(),
                        section.getPageStart(),
                        section.getPageEnd(),
                        mappingsBySectionId.getOrDefault(section.getSectionId(), List.of()).stream()
                                .map(mapping -> toSectionItemView(mapping,
                                        roundLabelsByTeachesId.getOrDefault(mapping.getTeachesId(), List.of())))
                                .toList()))
                .toList();
    }

    @Override
    public List<ProjectService.CurriculumUsingProject> findUsedProjects(UUID materialId, UUID orgId) {
        List<UUID> versionIds = curriculumVersionRepository
                .findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId).stream()
                .map(CurriculumVersion::getVersionId)
                .toList();
        if (versionIds.isEmpty()) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND);
        }
        return projectService.findProjectsUsingCurricula(versionIds, orgId);
    }

    @Override
    public List<UUID> findComparableCohorts(UUID cohortId, UUID orgId) {
        if (!catalogRepository.cohortExists(cohortId, orgId)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
        }
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
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_REQUIRED);
        }

        String normalizedTitle = title.trim().replaceAll("\\s+", " ").toLowerCase();

        if (materialRepository.existsByOrgIdAndNormalizedTitle(orgId, normalizedTitle)) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_TITLE_DUPLICATED);
        }

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

        byte[] pdfBytes = readFileBytes(version.getFileUri());

        int nextAnalysisVersion = (int) analysisRepository.countByVersionId(version.getVersionId()) + 1;

        String idempotencyKeyString = version.getVersionId() + ":" + nextAnalysisVersion;

        AiCurriculumClient.CurriculumAccepted accepted =
                aiCurriculumClient.requestAnalysis(version.getVersionId(), "Spring", pdfBytes, idempotencyKeyString);

        UUID idempotencyKeyUuid = UUID.nameUUIDFromBytes(idempotencyKeyString.getBytes(StandardCharsets.UTF_8));
        String requestFingerprint = sha256Hex(idempotencyKeyString);

        UUID placeholderModelId = jdbcTemplate.queryForObject(
                "SELECT model_id FROM ai_model LIMIT 1", UUID.class);

        CurriculumAnalysis analysis = CurriculumAnalysis.createInitial(
                version.getVersionId(), placeholderModelId, nextAnalysisVersion,
                idempotencyKeyUuid, requestFingerprint, actorUserId);
        analysis.updateExternalJobId(accepted.jobId());
        analysisRepository.save(analysis);
    }

    @Scheduled(fixedDelay = 600000)
    public void pollPendingCurriculumAnalyses() {
        List<CurriculumAnalysis> pendingList = analysisRepository
                .findAllByStatusIn(List.of(CurriculumAnalysisStatus.PENDING, CurriculumAnalysisStatus.RUNNING));

        log.info("### DEBUG 스케줄러 실행됨, 대기 중인 분석 건수={}", pendingList.size());

        for (CurriculumAnalysis analysis : pendingList) {
            log.info("### DEBUG 처리 시도: analysisId={}, status={}", analysis.getAnalysisId(), analysis.getStatus());
            try {
                self.reconcileOne(analysis);
            } catch (Exception e) {
                log.warn("교안 분석 스케줄러 폴링 중 오류: analysisId={}", analysis.getAnalysisId(), e);
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void reconcileOne(CurriculumAnalysis analysis) {
        UUID jobId = analysis.getExternalJobId();
        if (jobId == null) {
            return;
        }

        if (analysis.getRequestedAt() != null
                && analysis.getRequestedAt().isBefore(OffsetDateTime.now().minus(MAX_WAIT_DURATION))) {
            markAnalysisFailed(analysis, "MODEL_TIMEOUT",
                    "최대 대기 시간(" + MAX_WAIT_DURATION.toMinutes() + "분)을 초과했습니다. jobId=" + jobId);
            return;
        }

        AiCurriculumClient.AnalysisResult result;
        try {
            result = aiCurriculumClient.checkStatus(jobId.toString());
        } catch (Exception e) {
            log.warn("AI 분석 상태 조회 실패 (jobId={})", jobId, e);
            return;
        }

        if (result == null) {
            log.warn("### DEBUG result가 null입니다. jobId={}", jobId);
            return;
        }

        log.info("### DEBUG result 수신: status={}, sections size={}",
                result.status(), result.sections() != null ? result.sections().size() : -1);

        String status = result.status();

        if ("FAILED".equalsIgnoreCase(status)) {
            markAnalysisFailed(analysis, "PROVIDER_ERROR", "AI 서버가 분석 실패를 반환했습니다. jobId=" + jobId);
            return;
        }

        if (result.sections() == null || result.sections().isEmpty()) {
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                markAnalysisFailed(analysis, "INVALID_AI_RESPONSE", "AI 분석이 SUCCEEDED이나 섹션이 비어 있습니다. jobId=" + jobId);
            }
            return;
        }

        CurriculumVersion version = curriculumVersionRepository.findById(analysis.getVersionId())
                .orElse(null);
        if (version == null) {
            log.warn("교안 분석 결과 저장 실패: version을 찾을 수 없음 (analysisId={}, versionId={})",
                    analysis.getAnalysisId(), analysis.getVersionId());
            return;
        }

        CurriculumMaterial material = materialRepository.findById(version.getMaterialId())
                .orElse(null);
        if (material == null) {
            log.warn("교안 분석 결과 저장 실패: material을 찾을 수 없음 (analysisId={}, versionId={})",
                    analysis.getAnalysisId(), analysis.getVersionId());
            return;
        }

        persistAnalysisResult(analysis, version, material.getOrgId(), result);
    }

    private void persistAnalysisResult(CurriculumAnalysis analysis, CurriculumVersion version,
                                       UUID orgId, AiCurriculumClient.AnalysisResult result) {
        analysis.start();

        int sectionSeq = 1;
        // ✅ DB 중복 에러 해결: mappingSeq를 밖으로 꺼내서 순번이 누적되게 처리
        int mappingSeq = 1;

        for (AiCurriculumClient.SectionResult sec : result.sections()) {
            CurriculumSection savedSection = sectionRepository.save(
                    CurriculumSection.builder()
                            .versionId(version.getVersionId())
                            .sourceAnalysisId(analysis.getAnalysisId())
                            .sequenceNo(sectionSeq++)
                            .title(sec.title())
                            .pageStart(sec.pageStart())
                            .pageEnd(sec.pageEnd())
                            .keywords(sec.keywords())
                            .confidence(sec.confidence())
                            .build());

            for (AiCurriculumClient.TeachesResult t : sec.teaches()) {
                Teaches teaches = teachesRepository
                        .findByOrgIdAndNormalizedName(orgId, t.normalizedName())
                        .orElseGet(() -> teachesRepository.save(
                                Teaches.builder()
                                        .orgId(orgId)
                                        .canonicalName(t.canonicalName())
                                        .normalizedName(t.normalizedName())
                                        .canonicalDescription(
                                                t.description() != null ? t.description() : t.canonicalName())
                                        .build()));

                mappingRepository.save(
                        CurriculumTeachesMapping.builder()
                                .orgId(orgId)
                                .teachesId(teaches.getTeachesId())
                                .versionId(version.getVersionId())
                                .sectionId(savedSection.getSectionId())
                                .sourceAnalysisId(analysis.getAnalysisId())
                                .extractedName(t.canonicalName())
                                .sourceDescription(t.description())
                                .pageStart(sec.pageStart())
                                .pageEnd(sec.pageEnd())
                                .sourcePages(List.of(sec.pageStart()))
                                .sequenceNo(mappingSeq++) // 순번이 꼬이지 않음
                                .confidence(t.confidence())
                                .mappingStatus(MappingStatus.ACTIVE)
                                .build());
            }
        }

        analysis.succeed();
        analysisRepository.save(analysis);
    }

    private void markAnalysisFailed(CurriculumAnalysis analysis, String failureCode, String failureReason) {
        log.warn("교안 분석 실패 처리: analysisId={}, code={}, reason={}",
                analysis.getAnalysisId(), failureCode, failureReason);

        if (analysis.getStartedAt() == null) {
            analysis.start();
        }
        analysis.fail(failureCode, "CONCEPT_EXTRACTION", failureReason, true, "RETRY_SAME_FILE");
        analysisRepository.save(analysis);
    }

    @Override
    public CurriculumCatalogPage findCatalog(UUID orgId, String query, CurriculumAnalysisStatus status,
                                             boolean notAnalyzedOnly,
                                             CurriculumCatalogSort sort, int page, int size) {
        if (status != null && notAnalyzedOnly) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILTER_CONFLICT);
        }

        CurriculumCatalogRepository.CurriculumCatalogCriteria criteria =
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(
                        orgId, query, status, notAnalyzedOnly, sort == null ? CurriculumCatalogSort.RECENT : sort);

        long totalElements = catalogRepository.count(criteria);
        List<CurriculumCatalogRepository.CurriculumCatalogRow> content =
                catalogRepository.findPage(criteria, size, (long) page * size);

        Map<CurriculumAnalysisStatus, Long> statusCounts =
                new LinkedHashMap<>(catalogRepository.countByAnalysisStatus(orgId));
        for (CurriculumAnalysisStatus value : CurriculumAnalysisStatus.values()) {
            statusCounts.putIfAbsent(value, 0L);
        }

        long analyzed = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long notAnalyzedCount = Math.max(0, catalogRepository.count(
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(
                        orgId, null, null, false, criteria.sort())) - analyzed);

        int totalPages = (int) ((totalElements + size - 1) / size);
        return new CurriculumCatalogPage(
                content, page, size, totalElements, totalPages, statusCounts, notAnalyzedCount);
    }

    @Override
    public CurriculumCatalogRepository.CurriculumCatalogRow findCatalogItem(UUID materialId, UUID orgId) {
        return catalogRepository.findOne(orgId, materialId)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ✅ S3 다운로드 로직 추가: S3_CLIENT 상수를 사용하도록 매칭
    private byte[] readFileBytes(String fileUri) {
        URI uri = URI.create(fileUri);
        try {
            if ("s3".equals(uri.getScheme())) {
                String bucket = uri.getHost();
                String key = uri.getPath();
                if (key != null && key.startsWith("/")) {
                    key = key.substring(1);
                }

                GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();

                return S3_CLIENT.getObject(getObjectRequest, ResponseTransformer.toBytes()).asByteArray();
            }

            return Files.readAllBytes(Paths.get(uri));
        } catch (Exception e) {
            log.error("파일 읽기 실패: {}", fileUri, e);
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_UNREADABLE);
        }
    }

    private List<SectionItemView> toSectionItemViews(List<CurriculumTeachesMapping> mappings, UUID orgId) {
        if (mappings.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> roundLabelsByTeachesId = projectService.findRoundLabelsByTeaches(
                mappings.stream().map(CurriculumTeachesMapping::getTeachesId).collect(Collectors.toSet()), orgId);

        return mappings.stream()
                .map(mapping -> toSectionItemView(mapping,
                        roundLabelsByTeachesId.getOrDefault(mapping.getTeachesId(), List.of())))
                .toList();
    }

    private SectionItemView toSectionItemView(CurriculumTeachesMapping mapping, List<String> usedRoundLabels) {
        boolean definitionMissing = mapping.getSourceDescription() == null || mapping.getSourceDescription().isBlank();

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