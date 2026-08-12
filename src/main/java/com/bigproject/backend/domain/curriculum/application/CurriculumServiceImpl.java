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
import com.bigproject.backend.domain.curriculum.domain.MappingStatus;
import com.bigproject.backend.domain.curriculum.domain.Teaches;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.TeachesRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;

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

    private static final S3Client S3_CLIENT = S3Client.builder()
            .region(Region.AP_SOUTHEAST_2)
            .build();

    // 폴링 파라미터를 상수로 분리 — 값을 바꿀 때 루프 코드를 뒤지지 않게 한다.
    // AI 저장소 config.py 주석 실측: 교안 분석은 minimax-m3 기준 최대 25분 걸린다
    // ("강사가 수업 전까지만 끝나면 되므로 허용"). 동기 요청 안에서 기다리는 건
    // 애초에 불가능한 시간대라, 이 상수들은 requestAnalysis()가 아니라
    // pollPendingCurriculumAnalyses() 스케줄러가 스케줄 주기 판단에만 참고한다.
    private static final int POLL_MAX_ATTEMPTS = 100;
    private static final long POLL_INTERVAL_MS = 3000L;

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

    // self-invocation으로 @Transactional이 무시되는 문제 우회용.
    // pollPendingCurriculumAnalyses()가 reconcileOne()을 같은 객체 안에서 직접 호출하면
    // Spring AOP 프록시를 안 거쳐서 @Transactional이 통째로 무시된다 — 이게 실제로
    // markAnalysisFailed()가 로그는 남기는데 DB에 반영 안 되던 버그의 원인이었다.
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private CurriculumServiceImpl self;

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

    /**
     * 교안 재분석을 AI 서버에 요청한다.
     *
     * <p>🔴 정식 방식(2026-08-11 전환) — AI 저장소 config.py 실측 주석 기준
     * 교안 분석은 minimax-m3로 <b>최대 25분</b> 걸린다("강사가 수업 전까지만
     * 끝나면 되므로 허용"). 이 시간대는 하나의 HTTP 요청 안에서 동기로 기다릴
     * 수 있는 범위가 아니다 — 실제로 임시 동기 폴링(90초→180초→300초로 늘려가며
     * 시도)으로는 단 한 번도 정상 완료를 관측하지 못했고, 항상 MODEL_TIMEOUT
     * 아니면 (드물게) 빈 결과로 끝났다.
     *
     * <p>그래서 이 메서드는 AI 서버에 분석을 요청하고 job_id를 저장한 뒤
     * <b>즉시 리턴</b>한다. 실제 결과 회수는 {@link #pollPendingCurriculumAnalyses()}
     * 스케줄러가 이 트랜잭션과 무관하게 별도 주기로 수행한다.
     */
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
                aiCurriculumClient.requestAnalysis(version.getVersionId(), "Spring", pdfBytes, idempotencyKeyString);

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

        // 여기서 끝. 폴링하지 않는다 — pollPendingCurriculumAnalyses() 스케줄러가 이어받는다.
    }

    /**
     * 스케줄러 — PENDING/RUNNING 상태로 남은 교안 분석 건을 주기적으로 확인한다.
     *
     * <p>AI 분석이 최대 25분 걸리므로, 이 메서드는 짧은 주기(예: 30초)로 계속
     * 돌면서 "혹시 끝났나"만 가볍게 확인한다. 각 건은 개별 트랜잭션(reconcileOne)
     * 으로 처리해 <b>하나가 오래 걸려도 나머지가 묶이지 않게</b> 한다.
     *
     * <p>⚠️ 이 메서드 자체에는 {@code @Transactional}을 걸지 않는다 — 걸면 순회
     * 전체가 하나의 트랜잭션이 되어 DB 커넥션을 필요 이상으로 오래 잡는다.
     */
    @Scheduled(fixedDelay = 600000)  // 1분 간격 (AI 최대 25분 소요 감안, 최대 약 25회 확인)
    public void pollPendingCurriculumAnalyses() {
        List<CurriculumAnalysis> pendingList = analysisRepository
                .findAllByStatusIn(List.of(CurriculumAnalysisStatus.PENDING, CurriculumAnalysisStatus.RUNNING));

        log.info("### DEBUG 스케줄러 실행됨, 대기 중인 분석 건수={}", pendingList.size());

        for (CurriculumAnalysis analysis : pendingList) {
            log.info("### DEBUG 처리 시도: analysisId={}, status={}", analysis.getAnalysisId(), analysis.getStatus());
            try {
                self.reconcileOne(analysis);
            } catch (Exception e) {
                // 한 건의 예외가 나머지 건 처리를 막으면 안 된다 — 로그만 남기고 다음 건으로.
                log.warn("교안 분석 스케줄러 폴링 중 오류: analysisId={}", analysis.getAnalysisId(), e);
            }
        }
    }

    /**
     * 분석 한 건의 상태를 확인하고, 끝났으면 결과를 저장한다.
     *
     * <p>짧은 트랜잭션 하나로 끝난다 — AI 서버 응답을 기다리는 동안은 트랜잭션
     * 밖이라(checkStatus 호출 자체는 이 메서드가 시작하기 전 조건이 아니라
     * 이 메서드 안에서 짧게 일어난다는 뜻), DB 커넥션을 오래 잡지 않는다.
     */

    // 스케줄러 확인 간격(1분) — AI팀 실측 최대 소요시간(25분, config.py 주석)에
    // 맞춰 25분 넘게 응답이 없으면 포기한다.
    private static final java.time.Duration MAX_WAIT_DURATION = java.time.Duration.ofMinutes(25);

    @Transactional
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
            return; // 다음 스케줄 주기에 재시도
        }

        if (result == null) {
            log.warn("### DEBUG result가 null입니다. jobId={}", jobId);
            return; // 아직 응답 없음, 다음 주기에 재시도
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
            return; // 아직 RUNNING/QUEUED 등 진행 중이면 그냥 다음 주기로 넘어간다
        }

        // 성공 — 결과 저장. CurriculumAnalysis에는 orgId가 없으므로 version → material 경로로 조회한다.
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

            int mappingSeq = 1;
            for (AiCurriculumClient.TeachesResult t : sec.teaches()) {
                Teaches teaches = teachesRepository
                        .findByOrgIdAndNormalizedName(orgId, t.normalizedName())
                        .orElseGet(() -> teachesRepository.save(
                                Teaches.builder()
                                        .orgId(orgId)
                                        .canonicalName(t.canonicalName())
                                        .normalizedName(t.normalizedName())
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
                                .sequenceNo(mappingSeq++)
                                .confidence(t.confidence())
                                .mappingStatus(MappingStatus.ACTIVE)
                                .build());
            }
        }

        analysis.succeed();
        analysisRepository.save(analysis);
    }

    /**
     * 분석 실행을 FAILED로 전환한다.
     *
     * <p>주의: {@code curriculum_analysis} 테이블 제약상 FAILED는
     * started_at·failed_at·failure_code·failure_stage·failure_reason·
     * is_retryable·recovery_action이 모두 필수다.
     */
    private void markAnalysisFailed(CurriculumAnalysis analysis, String failureCode, String failureReason) {
        log.warn("교안 분석 실패 처리: analysisId={}, code={}, reason={}",
                analysis.getAnalysisId(), failureCode, failureReason);

        if (analysis.getStartedAt() == null) {
            analysis.start();
        }
        analysis.fail(failureCode, "CONCEPT_EXTRACTION", failureReason, /* isRetryable= */ true, /* recoveryAction= */ "RETRY_SAME_FILE");
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

    /**
     * 실제 업로드된 파일 바이트를 읽어 AI 서버로 전송한다.
     *
     * <p>S3 경로({@code s3://...})는 자격증명이 준비되기 전까지는 명시적으로
     * 예외를 던진다 — 조용히 더미 데이터를 반환하면 원인 파악이 훨씬 오래 걸린다.
     */
    private byte[] readFileBytes(String fileUri) {
        URI uri = URI.create(fileUri);
        try {
            if ("s3".equals(uri.getScheme())) {
                // TODO: AWS 자격증명 준비되면 아래로 교체.
                // String bucket = uri.getHost();
                // String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
                // GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
                // return S3_CLIENT.getObject(request, ResponseTransformer.toBytes()).asByteArray();
                throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_UNREADABLE);
            }
            return Files.readAllBytes(Paths.get(uri));
        } catch (IOException e) {
            throw new CurriculumException(CurriculumErrorCode.CURRICULUM_FILE_UNREADABLE);
        }
    }

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