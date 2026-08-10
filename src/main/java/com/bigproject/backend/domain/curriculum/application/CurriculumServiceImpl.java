package com.bigproject.backend.domain.curriculum.application;

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
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurriculumServiceImpl implements CurriculumService {

    private static final S3Client S3_CLIENT = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

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
        return toSectionItemViews(mappings, orgId);
    }

    /**
     * 교안의 섹션 전부 + 각 섹션의 가르친 항목.
     *
     * <p>11차 R1 — 예전에는 섹션마다 매핑을 따로 읽고, <b>항목마다</b> "쓰인 회차"를 물었다.
     * 그 조회가 내부에서 다시 개념·세트·프로젝트·기수의 회차 전량을 한 건씩 읽어서,
     * 항목 34개짜리 교안 하나에 쿼리가 수백 건 나가고 응답이 10초에 육박했다.
     * 지금은 <b>섹션 수·항목 수와 무관하게</b> 고정된 수의 조회로 끝난다.
     */
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

        // 항목 전부의 "쓰인 회차"를 한 번에 받는다.
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
    public List<ProjectService.CurriculumUsingProject> findUsedProjects(UUID versionId, UUID orgId) {
        return projectService.findProjectsUsingCurriculum(versionId, orgId);
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
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_REQUIRED);
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

        byte[] pdfBytes = readFileBytes(version.getFileUri());

        int nextAnalysisVersion = (int) analysisRepository.countByVersionId(version.getVersionId()) + 1;

        // AI 서버 호출용 사람이 읽는 idempotency 문자열 (헤더 값으로만 사용)
        String idempotencyKeyString = version.getVersionId() + ":" + nextAnalysisVersion;

        AiCurriculumClient.CurriculumAccepted accepted =
                aiCurriculumClient.requestAnalysis(version.getVersionId(), "AI", pdfBytes, idempotencyKeyString);

        // DB curriculum_analysis.idempotency_key는 UUID 타입 — 같은 문자열이면 항상 같은 UUID가 나오게 결정론적으로 변환
        UUID idempotencyKeyUuid = UUID.nameUUIDFromBytes(idempotencyKeyString.getBytes(StandardCharsets.UTF_8));
        // curriculum_analysis.request_fingerprint는 64자리 소문자 hex(CHECK 제약) — SHA-256으로 생성
        String requestFingerprint = sha256Hex(idempotencyKeyString);

        // TODO: 임시 우회 — ai_model 테이블에 실제 존재하는 아무 모델 하나를 가져와 FK 위반을 피한다.
        UUID placeholderModelId = jdbcTemplate.queryForObject(
                "SELECT model_id FROM ai_model LIMIT 1", UUID.class);

        CurriculumAnalysis analysis = CurriculumAnalysis.createInitial(
                version.getVersionId(), placeholderModelId, nextAnalysisVersion,
                idempotencyKeyUuid, requestFingerprint, actorUserId);
        analysis.updateExternalJobId(accepted.jobId());

        analysisRepository.save(analysis);
    }

    // ── 9차 R8: 기관 전체 교안 목록 · 단건 상세 ────────────────────────────────

    @Override
    public CurriculumCatalogPage findCatalog(UUID orgId, String query, CurriculumAnalysisStatus status,
                                             CurriculumCatalogSort sort, int page, int size) {
        CurriculumCatalogRepository.CurriculumCatalogCriteria criteria =
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(
                        orgId, query, status, sort == null ? CurriculumCatalogSort.RECENT : sort);

        long totalElements = catalogRepository.count(criteria);
        List<CurriculumCatalogRepository.CurriculumCatalogRow> content =
                catalogRepository.findPage(criteria, size, (long) page * size);

        // 상태별 개수는 필터와 무관한 기관 전체 모집단이다(11차 R7). 헤더의
        // `12개 · 분석 완료 9 · 실패 1`이 이 값이며, 걸러진 목록으로는 만들 수 없다.
        Map<CurriculumAnalysisStatus, Long> statusCounts =
                new LinkedHashMap<>(catalogRepository.countByAnalysisStatus(orgId));
        for (CurriculumAnalysisStatus value : CurriculumAnalysisStatus.values()) {
            statusCounts.putIfAbsent(value, 0L);
        }

        // 한 번도 분석하지 않은 교안은 상태가 없어 어느 키에도 안 들어간다. 전체에서 빼서 따로 센다 —
        // `분석 완료 + 실패`가 전체와 안 맞는 이유를 화면이 알 수 있어야 한다.
        long analyzed = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long notAnalyzedCount = Math.max(0, catalogRepository.count(
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(orgId, null, null, criteria.sort())) - analyzed);

        // 0건일 때 totalPages를 1로 만들지 않는다 — 빈 목록에 페이지가 하나 있다고 하면
        // 화면의 페이저가 존재하지 않는 페이지를 그린다.
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

    // TODO: 임시 우회 — S3 자격증명 문제로 실제 파일 대신 더미 바이트를 반환한다.
    // 나중에 AWS 키 받으면 아래 주석 처리된 원래 로직으로 되돌려야 한다.
    private byte[] readFileBytes(String fileUri) {
        return "dummy pdf content for testing".getBytes();
    }

    /*
    private byte[] readFileBytesOriginal(String fileUri) {
        URI uri = URI.create(fileUri);
        try {
            if ("s3".equals(uri.getScheme())) {
                String bucket = uri.getHost();
                String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
                GetObjectRequest request = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                return S3_CLIENT.getObject(request, ResponseTransformer.toBytes()).asByteArray();
            }
            return Files.readAllBytes(Paths.get(uri));
        } catch (IOException e) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_UNREADABLE);
        } catch (Exception e) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_UNREADABLE);
        }
    }
    */

    /** 섹션 하나짜리 경로. 여기서도 회차 라벨은 한 번에 받는다(11차 R1). */
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