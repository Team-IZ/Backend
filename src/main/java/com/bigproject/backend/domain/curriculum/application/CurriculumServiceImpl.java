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
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import com.bigproject.backend.global.exception.ApiException;

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
import java.util.HashMap;
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

    /**
     * 18차 R2 — 교안 목록에 분석 상태와 항목 수를 얹는다.
     *
     * <p>버전마다 최신 분석을 따로 읽으면 목록 하나에 조회가 교안 수만큼 붙으므로
     * (15차 R1에서 회차 목록이 같은 이유로 4.6초였다) 전량을 한 번에 읽고 자바에서 가른다.
     * 조회는 교안 수와 무관하게 <b>고정 3건</b>이다.
     */
    /**
     * 22차 R7 ② — 목록은 그대로 두고 <b>경로만 사실에 맞춘다.</b>
     *
     * <p>교안이 기관 단위라 결과를 기수로 좁히는 것은 사실이 아니다. 대신 없는 기수를 가리키는
     * 경로를 200으로 답하지 않는다 — 「이 기수엔 교안이 없다」와 「그런 기수가 없다」가 구분되지
     * 않으면 운영자가 지워진 기수 링크를 열고도 정상이라고 읽는다.
     */
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

        // 최신순으로 전량을 받아 버전별 첫 건만 취한다 — merge의 (first, second) -> first가 그 규칙이다.
        Map<UUID, CurriculumAnalysisStatus> statusByVersion = new HashMap<>();
        for (CurriculumAnalysis analysis : analysisRepository
                .findAllByVersionIdInOrderByRequestedAtDesc(versionIds)) {
            statusByVersion.putIfAbsent(analysis.getVersionId(), analysis.getStatus());
        }

        // 항목 수는 19차 R2에서 만든 일괄 집계를 그대로 쓴다. 후보 수와 같은 것을 세므로
        // 두 값이 갈릴 일이 없다 — 회차 생성 모달이 보는 수와 개념 후보 조회가 주는 수가 같아야 한다.
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

    /**
     * 13차 R1 — 받는 값이 <b>교안 ID</b>이며 그 교안의 <b>모든 버전</b>을 쓰는 회차를 모은다.
     *
     * <p>예전에는 이 자리를 교안 버전 ID로 읽었다. 경로가 {@code /curricula/{materialId}/projects}이고
     * 목록 응답도 {@code materialId}를 주므로 화면은 교안 ID를 넣었는데, 두 ID는 값이 겹치지 않아
     * <b>늘 빈 배열</b>이 됐다. 같은 화면의 {@code usedProjectCount}는 교안 기준으로 세고 있어
     * "목록은 24개 회차가 쓴다는데 상세는 0건"이 됐다.
     *
     * <p>모든 버전을 모으는 것은 {@code usedProjectCount}와 <b>같은 기준</b>이기 때문이다.
     * 최신 버전만 보면 지난 버전으로 연결된 회차가 빠져 두 숫자가 다시 갈린다.
     */
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
        // 22차 R8 — 「비교 대상이 없다」와 「기준 기수가 없다」를 가른다. 둘 다 빈 배열이면
        // 화면이 비교 드롭다운을 비워 두고 이유를 말하지 못한다.
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

        /*
         * 22차 R2 — 제목 충돌을 여기서 끊는다.
         *
         * uq_curriculum_material_org_id_normalized_title이 부분 인덱스가 아니라 전역 UNIQUE라
         * 논리 삭제된 교안도 제목을 계속 점유한다. 그래서 삭제 여부를 보지 않고 센다 —
         * 살아 있는 것만 세면 검사는 통과하고 INSERT가 DB에서 터져 코드 없는 500이 난다.
         *
         * 파일 크기와 무관하게 나므로 "50KB짜리도 500"이던 증상의 한 축이었다. 같은 제목으로
         * 다시 올리는 것은 등록이 아니라 새 버전이어야 하는데, 그 경로는 아직 없다.
         */
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
                                             boolean notAnalyzedOnly,
                                             CurriculumCatalogSort sort, int page, int size) {
        // 둘은 서로를 배제한다 — `분석 전`은 상태가 없는 교안이라 어떤 상태로도 좁혀지지 않는다(13차 R2).
        // 빈 목록을 조용히 주면 화면이 "그런 교안이 없다"로 읽으므로 입력 오류로 끊는다.
        if (status != null && notAnalyzedOnly) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILTER_CONFLICT);
        }

        CurriculumCatalogRepository.CurriculumCatalogCriteria criteria =
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(
                        orgId, query, status, notAnalyzedOnly, sort == null ? CurriculumCatalogSort.RECENT : sort);

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
                new CurriculumCatalogRepository.CurriculumCatalogCriteria(
                        orgId, null, null, false, criteria.sort())) - analyzed);

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