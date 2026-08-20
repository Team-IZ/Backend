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
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurriculumServiceImpl implements CurriculumService {

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
    private final S3Client s3Client;

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

    /**
     * 30차 Q2. 연결 링크를 한 번에 읽고, 거기 나온 버전들만 대상으로 상태·개념 수를 한 번씩 더 읽는다 —
     * {@link #findLinkableCurriculaWithStatus}와 같은 방식이라 조회 수가 교안 수에 비례하지 않는다.
     */
    @Override
    public List<LinkedCurriculum> findLinkedCurriculaForCohort(UUID cohortId, UUID orgId) {
        if (!catalogRepository.cohortExists(cohortId, orgId)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
        }

        List<ProjectService.CohortCurriculumLink> links =
                projectService.findCurriculumLinksInCohort(cohortId, orgId);
        if (links.isEmpty()) {
            return List.of();
        }

        // 한 교안이 여러 회차에 걸리므로 버전 단위로 접는다. 링크가 이미 차수 오름차순이라
        // LinkedHashMap이 그 순서를 그대로 유지한다.
        Map<UUID, List<ProjectService.CohortCurriculumLink>> linksByVersion = links.stream()
                .collect(Collectors.groupingBy(ProjectService.CohortCurriculumLink::versionId,
                        LinkedHashMap::new, Collectors.toList()));
        List<UUID> versionIds = List.copyOf(linksByVersion.keySet());

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

        Map<UUID, CurriculumVersion> versionById = curriculumVersionRepository.findAllById(versionIds).stream()
                .collect(Collectors.toMap(CurriculumVersion::getVersionId, version -> version));

        return linksByVersion.entrySet().stream()
                // 연결은 남아 있는데 버전이 사라진 경우는 건너뛴다. 링크 FK가 RESTRICT라 정상적으로는
                // 생기지 않지만, 여기서 터지면 화면 전체가 500이 된다.
                .filter(entry -> versionById.containsKey(entry.getKey()))
                .map(entry -> new LinkedCurriculum(
                        versionById.get(entry.getKey()),
                        statusByVersion.get(entry.getKey()),
                        Math.toIntExact(teachesByVersion.getOrDefault(entry.getKey(), 0L)),
                        entry.getValue()))
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
                .orElseThrow(() -> {
                    // 성공한 분석이 없다고 다 같은 상태가 아니다 — 최근 시도가 FAILED로 끝난 것과
                    // 아직 안 끝난 것(미시작·PENDING·RUNNING)을 갈라야, 화면이 "곧 끝납니다"를
                    // 영구 실패인 교안에도 그려서 사용자를 하염없이 기다리게 만드는 일이 없다.
                    boolean latestAttemptFailed = analysisRepository
                            .findFirstByVersionIdOrderByRequestedAtDesc(versionId)
                            .map(analysis -> analysis.getStatus() == CurriculumAnalysisStatus.FAILED)
                            .orElse(false);
                    return new CurriculumException(latestAttemptFailed
                            ? CurriculumErrorCode.CURRICULUM_ANALYSIS_FAILED
                            : CurriculumErrorCode.CURRICULUM_ANALYSIS_NOT_COMPLETED);
                });

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
    public List<CurriculumVersion> findVersionHistory(UUID materialId, UUID orgId) {
        if (!materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND);
        }
        return curriculumVersionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId);
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

    /**
     * 42차 R1 — {@code POST /curricula/{materialId}/versions}.
     *
     * <p>기존에 {@link CurriculumVersion#createNextVersion}·
     * {@link CurriculumVersionRepository#findAllByMaterialIdAndOrgIdOrderByVersionNoDesc}가 이미
     * 있었는데 이들을 잇는 서비스 메서드가 없어 실제로 새 버전을 만들 경로가 없었다(42차 문서가
     * 지적한 그대로). 그 둘을 그대로 이어 붙인다 — 새 판정·새 번호 규칙을 만들지 않는다.
     *
     * <p>새 버전을 등록해도 이전 버전을 {@link CurriculumVersion#deactivate()}로 내리지 않는다 —
     * 모든 버전이 계속 ACTIVE로 남는다.
     *
     * <p>제목 UNIQUE 검사(§ {@code existsByOrgIdAndNormalizedTitle})는 제목을
     * <b>바꿔 달 때만</b> 돈다. 기존 material에 버전만 추가하는 것이므로, 제목을 그대로 두면 자기
     * 자신의 제목과 부딪힐 이유가 없다(42차 §1 "①로 가면 제목 유니크 제약은 그대로 두셔도
     * 됩니다").
     */
    @Override
    @Transactional
    public CurriculumVersion registerCurriculumVersion(
            UUID materialId, UUID orgId, String title, MultipartFile file, UUID actorUserId) {
        if (file == null || file.isEmpty()) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_REQUIRED);
        }

        CurriculumMaterial material = materialRepository.findByMaterialIdAndOrgId(materialId, orgId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

        if (title != null && !title.isBlank()) {
            String normalizedTitle = title.trim().replaceAll("\\s+", " ").toLowerCase();
            if (!normalizedTitle.equals(material.getNormalizedTitle())
                    && materialRepository.existsByOrgIdAndNormalizedTitle(orgId, normalizedTitle)) {
                throw new CurriculumException(CurriculumErrorCode.CURRICULUM_TITLE_DUPLICATED);
            }
            material.updateTitle(title, normalizedTitle);
        }

        List<CurriculumVersion> versions = curriculumVersionRepository
                .findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId);
        // materialRepository 조회를 이미 통과했으므로(위) 첫 버전이 없을 수 없다 — 방어적으로만 막는다.
        CurriculumVersion currentLatest = versions.stream().findFirst()
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

        FileStorageService.StoredFile stored = fileStorageService.store(file);

        // 기존 버전 행은 지우거나 바꾸지 않는다 — 새 버전을 올려도 이전 버전을 비활성화하지
        // 않으므로 모든 버전이 계속 ACTIVE로 남는다(연결 가능한 후보 목록에도 계속 뜬다).
        CurriculumVersion version = CurriculumVersion.createNextVersion(
                materialId, currentLatest.getVersionNo() + 1, stored.originalFileName(), stored.fileUri(),
                stored.fileSizeBytes(), stored.contentHash(), actorUserId);

        return curriculumVersionRepository.save(version);
    }

    @Override
    @Transactional
    public void requestAnalysis(UUID materialId, UUID orgId, UUID actorUserId, boolean force) {
        if (!materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND);
        }

        CurriculumVersion version = curriculumVersionRepository
                .findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId)
                .stream().findFirst()
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

        // 25차 R2 — 진행 중인 분석이 있으면 여기서 끊는다. AI에 보내기 전에 판정해야
        // 중복 요청 한 건마다 LLM 비용이 한 번 더 나가는 것을 막을 수 있다.
        if (!force) {
            boolean alreadyRunning = !analysisRepository.findAllByVersionIdAndStatusIn(
                    version.getVersionId(),
                    List.of(CurriculumAnalysisStatus.PENDING, CurriculumAnalysisStatus.RUNNING)).isEmpty();
            if (alreadyRunning) {
                throw new CurriculumException(CurriculumErrorCode.CURRICULUM_ANALYSIS_IN_PROGRESS);
            }
        }

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
    public CurriculumCatalogPage findCatalog(UUID orgId, String query, List<CurriculumAnalysisStatus> statuses,
                                             boolean notAnalyzedOnly,
                                             CurriculumCatalogSort sort, int page, int size) {
        // 25차 R1 — 같은 값을 두 번 보내도 IN 목록만 길어질 뿐이라 여기서 한 번 정리한다.
        List<CurriculumAnalysisStatus> distinctStatuses = statuses == null
                ? List.of()
                : statuses.stream().filter(Objects::nonNull).distinct().toList();

        if (!distinctStatuses.isEmpty() && notAnalyzedOnly) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILTER_CONFLICT);
        }

        CurriculumCatalogRepository.CurriculumCatalogCriteria criteria =
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(
                        orgId, query, distinctStatuses, notAnalyzedOnly,
                        sort == null ? CurriculumCatalogSort.RECENT : sort);

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
                        orgId, null, List.of(), false, criteria.sort())) - analyzed);

        int totalPages = (int) ((totalElements + size - 1) / size);
        return new CurriculumCatalogPage(
                content, page, size, totalElements, totalPages, statusCounts, notAnalyzedCount);
    }

    @Override
    public CurriculumCatalogRepository.CurriculumCatalogRow findCatalogItem(UUID materialId, UUID orgId) {
        return catalogRepository.findOne(orgId, materialId)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));
    }

    /**
     * 25차 R11 — 교안 논리 삭제.
     *
     * <p>연결 판정에 {@code findCatalogItem}의 {@code usedProjectCount}를 그대로 쓴다. 목록·상세가
     * `3개 회차에서 사용 중`으로 보여 주는 값과 <b>같은 식</b>이라, 화면이 0으로 읽은 교안이
     * 삭제에서만 409가 되는 어긋남이 생기지 않는다.
     *
     * <p>행을 지우지 않고 {@code deleted_at}만 찍는다 — 분석·섹션·개념 매핑이 이 교안을 참조하고
     * 있어 물리 삭제는 이력을 함께 지운다.
     *
     * <p>제목은 더 이상 점유되지 않는다 — {@link CurriculumMaterial#softDelete()}가 삭제와 동시에
     * {@code normalizedTitle}을 materialId 기반의 유일한 값으로 봉인한다. 부분 유니크 인덱스
     * 마이그레이션 없이도, 지운 교안과 같은 제목으로 다시 올릴 수 있다.
     */
    @Override
    @Transactional
    public void deleteCurriculum(UUID materialId, UUID orgId, UUID actorUserId) {
        CurriculumMaterial material = materialRepository.findByMaterialIdAndOrgId(materialId, orgId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

        long usedProjectCount = findCatalogItem(materialId, orgId).usedProjectCount();
        if (usedProjectCount > 0) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_IN_USE,
                    "이 교안을 쓰는 회차가 " + usedProjectCount + "건 있어 삭제할 수 없습니다.");
        }

        material.softDelete();
        materialRepository.save(material);
        log.info("교안 논리 삭제: materialId={}, orgId={}, actorUserId={}", materialId, orgId, actorUserId);
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

                return s3Client.getObject(getObjectRequest, ResponseTransformer.toBytes()).asByteArray();
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