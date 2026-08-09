package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumSection;
import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.MappingStatus;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.domain.ConceptSetStatus;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectListSort;
import com.bigproject.backend.domain.projectexecution.domain.ProjectRequirement;
import com.bigproject.backend.domain.projectexecution.domain.ProjectVerificationConcept;
import com.bigproject.backend.domain.projectexecution.domain.ProjectVerificationConceptSet;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectCurriculumRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectRequirementRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectVerificationConceptRepository;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectVerificationConceptSetRepository;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.projectexecution.domain.ProjectExecutionErrorCode;
import com.bigproject.backend.global.exception.ApiException;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;
    private final ProjectVerificationConceptRepository verificationConceptRepository;
    private final ProjectVerificationConceptSetRepository verificationConceptSetRepository;
    private final ProjectCurriculumRepository projectCurriculumRepository;
    private final CurriculumTeachesMappingRepository mappingRepository;
    private final CurriculumVersionRepository curriculumVersionRepository;
    private final CurriculumAnalysisRepository curriculumAnalysisRepository;
    private final CurriculumSectionRepository curriculumSectionRepository;
    private final ProjectDependencyRepository projectDependencyRepository;

    @Override
    @Transactional
    public void markRunning(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));
        project.start(actorUserId);
    }

    @Override
    @Transactional
    public List<ProjectRequirement> replaceRequirements(
            UUID projectId, UUID orgId, List<String> requirementTitles, UUID actorUserId) {

        if (!projectRepository.existsByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)) {
            throw new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND);
        }

        List<ProjectRequirement> existing = requirementRepository
                .findAllByProjectIdAndOrgIdAndActiveTrueOrderBySequenceNoAsc(projectId, orgId);
        Map<String, ProjectRequirement> existingByKey = existing.stream()
                .collect(Collectors.toMap(ProjectRequirement::getRequirementKey, r -> r));

        Set<String> incomingKeys = new HashSet<>();
        List<ProjectRequirement> result = new ArrayList<>();

        int sequenceNo = 1;
        for (String rawTitle : requirementTitles) {
            String title = normalize(rawTitle);
            if (title.isEmpty()) {
                continue;
            }
            String key = deriveKey(title);
            incomingKeys.add(key);

            ProjectRequirement existingRequirement = existingByKey.get(key);
            if (existingRequirement != null) {
                existingRequirement.changeSequenceNo(sequenceNo);
                result.add(existingRequirement);
            } else {
                ProjectRequirement created = ProjectRequirement.createFirstVersion(
                        projectId, orgId, key, sequenceNo, title, "", actorUserId);
                result.add(requirementRepository.save(created));
            }
            sequenceNo++;
        }

        for (ProjectRequirement r : existing) {
            if (!incomingKeys.contains(r.getRequirementKey())) {
                r.retire();
            }
        }

        return result;
    }

    private String normalize(String rawTitle) {
        if (rawTitle == null) return "";
        return rawTitle.trim().replaceAll("\\s+", " ");
    }

    private String deriveKey(String normalizedTitle) {
        return UUID.nameUUIDFromBytes(normalizedTitle.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String resolveMiniProjectRoundLabel(UUID projectId, UUID orgId) {
        Project project = projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));

        if (project.getProjectCategory() != ProjectCategory.MINI_PROJECT) {
            throw new ApiException(ProjectExecutionErrorCode.BIG_PROJECT_HAS_NO_ROUND_LABEL);
        }

        List<Project> miniProjectsInOrder = projectRepository
                .findByCohortIdAndOrgIdAndProjectCategoryAndDeletedAtIsNullOrderBySequenceNoAsc(
                        project.getCohortId(), orgId, ProjectCategory.MINI_PROJECT);

        int roundNo = -1;
        for (int i = 0; i < miniProjectsInOrder.size(); i++) {
            if (miniProjectsInOrder.get(i).getProjectId().equals(projectId)) {
                roundNo = i + 1;
                break;
            }
        }
        if (roundNo == -1) {
            throw new ApiException(ProjectExecutionErrorCode.ROUND_NUMBER_UNRESOLVED);
        }
        return "미프 " + roundNo + "차";
    }

    @Override
    public List<String> findRoundLabelsUsingTeaches(UUID teachesId, UUID orgId) {
        List<ProjectVerificationConcept> concepts = verificationConceptRepository.findByTeachesId(teachesId);

        List<String> labels = new ArrayList<>();
        for (ProjectVerificationConcept concept : concepts) {
            ProjectVerificationConceptSet set = verificationConceptSetRepository.findById(concept.getConceptSetId())
                    .orElse(null);
            if (set == null || set.getStatus() != ConceptSetStatus.ACTIVE) {
                continue;
            }
            labels.add(resolveMiniProjectRoundLabel(set.getProjectId(), orgId));
        }
        return labels;
    }

    @Override
    public List<String> findRoundLabelsUsingCurriculum(UUID curriculumVersionId, UUID orgId) {
        List<ProjectCurriculum> links = projectCurriculumRepository.findAllByCurriculumVersionId(curriculumVersionId);

        return links.stream()
                .map(link -> resolveMiniProjectRoundLabel(link.getProjectId(), orgId))
                .toList();
    }

    @Override
    @Transactional
    public Project createProject(UUID orgId, UUID cohortId, String name, ProjectCategory category,
                                 LocalDate startDate, LocalDate endDate, UUID actorUserId) {
        // 삭제된 회차도 이름·순번을 계속 점유한다 — 두 UNIQUE 제약이 부분 인덱스가 아니기 때문이다.
        // 살아 있는 것만 세면 검사는 통과하고 INSERT가 DB에서 터져 500이 난다(ProjectRepository 주석 참고).
        if (projectRepository.existsByCohortIdAndOrgIdAndName(cohortId, orgId, name)) {
            throw new ApiException(ProjectExecutionErrorCode.PROJECT_NAME_DUPLICATED,
                    "이미 존재하는 프로젝트명입니다(삭제된 회차의 이름도 다시 쓸 수 없습니다): " + name);
        }
        int nextSequenceNo = projectRepository.findMaxSequenceNo(cohortId, orgId) + 1;

        Project project = category == ProjectCategory.MINI_PROJECT
                ? Project.createMiniProject(orgId, cohortId, name, nextSequenceNo, startDate, endDate, actorUserId)
                : Project.createBigProject(orgId, cohortId, name, nextSequenceNo, startDate, endDate, actorUserId);

        return projectRepository.save(project);
    }

    @Override
    public List<Project> findProjects(UUID cohortId, UUID orgId) {
        return projectRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByCreatedAtDesc(cohortId, orgId);
    }

    @Override
    public Project findProject(UUID projectId, UUID orgId) {
        return projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));
    }

    @Override
    @Transactional
    public Project updateSchedule(UUID projectId, UUID orgId, LocalDate startDate, LocalDate endDate, UUID actorUserId) {
        Project project = findProject(projectId, orgId);
        project.updateSchedule(startDate, endDate, actorUserId);
        return project;
    }

    @Override
    public List<UUID> findLinkedCurriculumVersionIds(UUID cohortId, UUID orgId) {
        List<Project> projects = projectRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByCreatedAtDesc(cohortId, orgId);
        List<UUID> versionIds = new ArrayList<>();
        for (Project project : projects) {
            for (ProjectCurriculum link : projectCurriculumRepository.findAllByProjectIdAndOrgId(project.getProjectId(), orgId)) {
                if (!versionIds.contains(link.getCurriculumVersionId())) {
                    versionIds.add(link.getCurriculumVersionId());
                }
            }
        }
        return versionIds;
    }

    @Override
    public List<UUID> findCohortsSharingAnyCurriculum(List<UUID> curriculumVersionIds, UUID excludeCohortId, UUID orgId) {
        List<UUID> cohortIds = new ArrayList<>();
        for (UUID versionId : curriculumVersionIds) {
            for (ProjectCurriculum link : projectCurriculumRepository.findAllByCurriculumVersionId(versionId)) {
                Project project = projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(link.getProjectId(), orgId).orElse(null);
                if (project == null || project.getCohortId().equals(excludeCohortId)) {
                    continue;
                }
                if (!cohortIds.contains(project.getCohortId())) {
                    cohortIds.add(project.getCohortId());
                }
            }
        }
        return cohortIds;
    }

    /**
     * 9차 R2 — 후보 항목을 {@code findSections}의 항목과 같은 모양으로 맞췄다.
     *
     * <p>후보를 고르는 화면이 <b>교안·섹션별로 묶어서</b> 그리는데 예전 응답에는 묶을 기준
     * ({@code curriculumVersionId}·{@code sectionId}·{@code sectionTitle})도, `p.53` 표기에 쓸
     * 페이지 범위도 없었다. 같은 원장(curriculum_teaches_mapping)에서 나오는 값이라 좁게 줄 이유가 없다.
     *
     * <p>섹션 제목은 매핑마다 되묻지 않고 등장하는 섹션을 한 번에 읽어 붙인다.
     */
    @Override
    public List<ConceptCandidate> findConceptCandidates(UUID projectId, UUID orgId) {
        findProject(projectId, orgId);

        List<ProjectCurriculum> links = projectCurriculumRepository.findAllByProjectIdAndOrgId(projectId, orgId);

        List<CurriculumTeachesMapping> mappings = new ArrayList<>();
        for (ProjectCurriculum link : links) {
            mappings.addAll(mappingRepository.findActiveCandidatesByVersion(
                    link.getCurriculumVersionId(), orgId, MappingStatus.ACTIVE));
        }

        Map<UUID, String> sectionTitleById = findSectionTitles(mappings);

        List<ConceptCandidate> candidates = new ArrayList<>();
        for (CurriculumTeachesMapping mapping : mappings) {
            // definitionMissing/description 규칙은 CurriculumServiceImpl.toSectionItemView와 같아야 한다.
            // 두 응답이 같은 컬럼을 서로 다르게 해석하면 화면이 같은 개념을 두 벌로 다루게 된다.
            boolean definitionMissing = mapping.getSourceDescription() == null
                    || mapping.getSourceDescription().isBlank();
            candidates.add(new ConceptCandidate(
                    mapping.getMappingId(),
                    mapping.getTeachesId(),
                    mapping.getExtractedName(),
                    definitionMissing ? null : mapping.getSourceDescription(),
                    definitionMissing,
                    mapping.getVersionId(),
                    mapping.getSectionId(),
                    sectionTitleById.get(mapping.getSectionId()),
                    mapping.getPageStart(),
                    mapping.getPageEnd()));
        }
        return candidates;
    }

    private Map<UUID, String> findSectionTitles(List<CurriculumTeachesMapping> mappings) {
        List<UUID> sectionIds = mappings.stream()
                .map(CurriculumTeachesMapping::getSectionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> titles = new HashMap<>();
        for (CurriculumSection section : curriculumSectionRepository.findAllById(sectionIds)) {
            titles.put(section.getSectionId(), section.getTitle());
        }
        return titles;
    }

    // ── 9차 R1: 저장한 것을 되읽는 조회 ──────────────────────────────────────────

    @Override
    public ProjectDetail findProjectDetail(UUID projectId, UUID orgId) {
        Project project = findProject(projectId, orgId);

        List<LinkedCurriculum> curricula = findLinkedCurricula(projectId, orgId);
        List<ConfirmedConcept> concepts = findConfirmedConcepts(projectId);
        List<String> requirementTitles = requirementRepository
                .findAllByProjectIdAndOrgIdAndActiveTrueOrderBySequenceNoAsc(projectId, orgId).stream()
                .map(ProjectRequirement::getTitle)
                .toList();

        int candidateCount = countConceptCandidates(projectId, orgId);
        ProjectSummary summary = new ProjectSummary(
                project, curricula.size(), concepts.size(), candidateCount);

        return new ProjectDetail(summary, curricula, concepts, requirementTitles);
    }

    @Override
    public List<ProjectSummary> findProjectSummaries(UUID cohortId, UUID orgId) {
        return findProjects(cohortId, orgId).stream()
                .map(project -> summarize(project, orgId))
                .toList();
    }

    // ── 9차 R3: 검색·필터·정렬·상태별 개수 ────────────────────────────────────

    /**
     * 기수당 회차가 6~8건이라 전량이 한 페이지에 들어가고 페이저도 없다. 그래서 SQL로 거르지 않고
     * <b>전량을 읽어 메모리에서 거르고 정렬한다</b> — 교안 필터가 project_curriculum·curriculum_version
     * 두 단계 조인이라 SQL로 옮기면 쿼리가 훨씬 복잡해지는데 얻는 것이 없다.
     *
     * <p>회차가 쌓이거나 페이지를 나눠야 할 때는 이 메서드 안만 SQL로 바꾸면 된다 —
     * 계약(파라미터·응답)은 그대로다.
     */
    @Override
    public ProjectList findProjectList(UUID cohortId, UUID orgId, ProjectListCriteria criteria) {
        List<Project> population = findProjects(cohortId, orgId);

        // 상태별 개수는 필터를 적용하지 않은 모집단이다 — 상태 칩이 자기 자신을 필터링하면
        // 언제나 자기 개수만 남아 다른 칩이 0이 된다. 0인 상태도 키를 채운다(키가 빠지는 것과 다르다).
        Map<ProjectLifecycleStatus, Long> counts = new EnumMap<>(ProjectLifecycleStatus.class);
        for (ProjectLifecycleStatus status : ProjectLifecycleStatus.values()) {
            counts.put(status, 0L);
        }
        population.forEach(project -> counts.merge(project.getLifecycleStatus(), 1L, Long::sum));

        String search = criteria.search() == null || criteria.search().isBlank()
                ? null
                : criteria.search().trim().toLowerCase(Locale.ROOT);

        List<ProjectSummary> filtered = new ArrayList<>();
        for (Project project : population) {
            if (criteria.status() != null && project.getLifecycleStatus() != criteria.status()) {
                continue;
            }
            if (search != null && !project.getName().toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }
            if (criteria.curriculumId() != null && !usesCurriculum(project.getProjectId(), orgId, criteria.curriculumId())) {
                continue;
            }
            filtered.add(summarize(project, orgId));
        }

        filtered.sort(comparatorFor(criteria.sort() == null ? ProjectListSort.READINESS : criteria.sort()));
        return new ProjectList(List.copyOf(filtered), counts);
    }

    /** 교안 버전 ID와 자료(material) ID를 모두 받는다 — 화면이 어느 쪽을 들고 있든 되도록. */
    private boolean usesCurriculum(UUID projectId, UUID orgId, UUID curriculumId) {
        for (ProjectCurriculum link : projectCurriculumRepository.findAllByProjectIdAndOrgId(projectId, orgId)) {
            if (link.getCurriculumVersionId().equals(curriculumId)) {
                return true;
            }
            boolean sameMaterial = curriculumVersionRepository
                    .findByVersionIdAndOrgId(link.getCurriculumVersionId(), orgId)
                    .map(version -> version.getMaterialId().equals(curriculumId))
                    .orElse(false);
            if (sameMaterial) {
                return true;
            }
        }
        return false;
    }

    /**
     * 정렬 기준. {@code READINESS}는 {@link ProjectSummary#unreadyCount()}를 쓴다 —
     * 응답의 {@code readiness} 배지와 <b>같은 규칙</b>이라 목록의 순서와 배지가 어긋날 수 없다(9차 Q1).
     */
    private Comparator<ProjectSummary> comparatorFor(ProjectListSort sort) {
        return switch (sort) {
            // 덜 준비된 것이 앞. 같으면 마감이 이른 순, 그것도 같으면 최근 회차 순.
            case READINESS -> Comparator.comparingInt(ProjectSummary::unreadyCount).reversed()
                    .thenComparing(summary -> summary.project().getEndDate(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(summary -> summary.project().getSequenceNo(),
                            Comparator.nullsLast(Comparator.reverseOrder()));
            case DUE_SOON -> Comparator.<ProjectSummary, LocalDate>comparing(
                    summary -> summary.project().getEndDate(), Comparator.nullsLast(Comparator.naturalOrder()));
            case START_DATE -> Comparator.<ProjectSummary, LocalDate>comparing(
                    summary -> summary.project().getStartDate(), Comparator.nullsLast(Comparator.naturalOrder()));
        };
    }

    @Override
    public ProjectSummary summarize(Project project, UUID orgId) {
        UUID projectId = project.getProjectId();
        long curriculumCount = projectCurriculumRepository.countByProjectIdAndOrgId(projectId, orgId);
        long conceptCount = verificationConceptSetRepository
                .findByProjectIdAndStatus(projectId, ConceptSetStatus.ACTIVE)
                .map(set -> verificationConceptRepository.countByConceptSetId(set.getConceptSetId()))
                .orElse(0L);
        return new ProjectSummary(
                project,
                Math.toIntExact(curriculumCount),
                Math.toIntExact(conceptCount),
                countConceptCandidates(projectId, orgId));
    }

    private List<LinkedCurriculum> findLinkedCurricula(UUID projectId, UUID orgId) {
        List<LinkedCurriculum> curricula = new ArrayList<>();
        for (ProjectCurriculum link : projectCurriculumRepository
                .findAllByProjectIdAndOrgIdOrderBySequenceNoAsc(projectId, orgId)) {
            // 파일명·버전은 curriculum_version에 있다. 버전이 사라지는 경로는 없지만, 없으면
            // 연결 자체를 감추는 대신 ID만 채워 내려보낸다 — 화면이 "교안 연결 안 됨"으로 잘못 읽지 않게.
            CurriculumVersion version = curriculumVersionRepository
                    .findByVersionIdAndOrgId(link.getCurriculumVersionId(), orgId)
                    .orElse(null);
            curricula.add(new LinkedCurriculum(
                    link.getProjectCurriculumId(),
                    link.getCurriculumVersionId(),
                    version == null ? null : version.getMaterialId(),
                    version == null ? null : version.getOriginalFileName(),
                    version == null ? null : version.getVersionNo(),
                    link.getLinkedAt()));
        }
        return curricula;
    }

    /** 활성 세트가 없으면 빈 목록이다 — 아직 개념을 확정하지 않은 회차이며, 0건과 구분할 것이 없다. */
    private List<ConfirmedConcept> findConfirmedConcepts(UUID projectId) {
        return verificationConceptSetRepository.findByProjectIdAndStatus(projectId, ConceptSetStatus.ACTIVE)
                .map(set -> verificationConceptRepository
                        .findByConceptSetIdOrderBySequenceNoAsc(set.getConceptSetId()).stream()
                        .map(this::toConfirmedConcept)
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * 확정 개념은 <b>출처를 달고 다녀야</b> 한다. 이름·페이지·교안 버전이 전부 원장(매핑)에 있으므로
     * source_mapping_id로 되짚어 채운다. 매핑이 없어진 경우에도 개념 자체는 내려보낸다 —
     * 확정 이력이 화면에서 통째로 사라지는 편이 더 나쁘다.
     */
    private ConfirmedConcept toConfirmedConcept(ProjectVerificationConcept concept) {
        CurriculumTeachesMapping mapping = mappingRepository.findById(concept.getSourceMappingId()).orElse(null);
        if (mapping == null) {
            return new ConfirmedConcept(
                    concept.getSourceMappingId(), concept.getTeachesId(), null, null, null, null);
        }
        return new ConfirmedConcept(
                mapping.getMappingId(),
                concept.getTeachesId(),
                mapping.getExtractedName(),
                mapping.getVersionId(),
                mapping.getPageStart(),
                mapping.getPageEnd());
    }

    /**
     * 후보 수만 센다. 화면이 세려면 회차마다 concept-candidates를 부르거나 교안 전량을 받아야 하는데,
     * 목록에 회차가 6개면 조회가 6건 더 나간다(9차 R1).
     */
    private int countConceptCandidates(UUID projectId, UUID orgId) {
        long total = 0;
        for (ProjectCurriculum link : projectCurriculumRepository.findAllByProjectIdAndOrgId(projectId, orgId)) {
            total += mappingRepository.countActiveCandidatesByVersion(
                    link.getCurriculumVersionId(), orgId, MappingStatus.ACTIVE);
        }
        return Math.toIntExact(total);
    }

    @Override
    @Transactional
    public void confirmConcepts(UUID projectId, UUID orgId, List<UUID> mappingIds, UUID actorUserId) {
        findProject(projectId, orgId);

        int nextVersionNo = verificationConceptSetRepository.findByProjectIdAndStatus(projectId, ConceptSetStatus.ACTIVE)
                .map(existing -> {
                    existing.supersede(OffsetDateTime.now());
                    return existing.getVersionNo() + 1;
                })
                .orElse(1);

        ProjectVerificationConceptSet newSet = ProjectVerificationConceptSet.activate(
                projectId, orgId, nextVersionNo, actorUserId, "검증개념 확정");
        verificationConceptSetRepository.save(newSet);

        int sequenceNo = 1;
        for (UUID mappingId : mappingIds) {
            CurriculumTeachesMapping mapping = mappingRepository.findById(mappingId)
                    .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.CONCEPT_MAPPING_NOT_FOUND,
                            "존재하지 않는 매핑입니다: " + mappingId));
            ProjectVerificationConcept concept = ProjectVerificationConcept.of(
                    newSet.getConceptSetId(), orgId, mapping.getTeachesId(), mappingId, sequenceNo++);
            verificationConceptRepository.save(concept);
        }
    }

    /**
     * 프로젝트-교안 연결. project_curriculum 테이블 DDL 제약을 그대로 코드로 옮긴 것:
     * - MINI_PROJECT에만 생성 가능 (BIG_PROJECT는 curriculum_not_applicable=TRUE 정책)
     * - Project·CurriculumVersion·Organization 경로 일치 (orgId로 양쪽 조회 시 이미 강제됨)
     * - 연결 대상 버전은 유효한 성공 분석 + 승인된(ACTIVE) 개념 매핑을 최소 1건 가져야 함
     * - UNIQUE(project_id, curriculum_version_id) 사전 체크로 409 명확히 반환
     * - sequence_no는 프로젝트 내 기존 최대값 + 1로 자동 채번 (UNIQUE(project_id, sequence_no) 대응)
     */
    @Override
    @Transactional
    public ProjectCurriculum linkCurriculum(UUID projectId, UUID orgId, UUID curriculumVersionId, UUID actorUserId) {
        Project project = findProject(projectId, orgId);

        if (project.getProjectCategory() != ProjectCategory.MINI_PROJECT) {
            throw new ApiException(ProjectExecutionErrorCode.CURRICULUM_NOT_APPLICABLE_TO_BIG_PROJECT);
        }

        CurriculumVersion version = curriculumVersionRepository.findByVersionIdAndOrgId(curriculumVersionId, orgId)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_VERSION_NOT_FOUND,
                        "교안 버전을 찾을 수 없습니다."));

        curriculumAnalysisRepository
                .findFirstByVersionIdAndStatusOrderByCompletedAtDesc(curriculumVersionId, CurriculumAnalysisStatus.SUCCEEDED)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.CURRICULUM_ANALYSIS_NOT_SUCCEEDED));

        List<CurriculumTeachesMapping> approvedMappings =
                mappingRepository.findActiveCandidatesByVersion(curriculumVersionId, orgId, MappingStatus.ACTIVE);
        if (approvedMappings.isEmpty()) {
            throw new ApiException(ProjectExecutionErrorCode.CURRICULUM_MAPPING_NOT_APPROVED);
        }

        if (projectCurriculumRepository.existsByProjectIdAndCurriculumVersionId(projectId, curriculumVersionId)) {
            throw new ApiException(ProjectExecutionErrorCode.CURRICULUM_ALREADY_LINKED);
        }

        int nextSequenceNo = projectCurriculumRepository.findAllByProjectIdOrderBySequenceNoDesc(projectId)
                .stream().findFirst().map(pc -> pc.getSequenceNo() + 1).orElse(1);

        ProjectCurriculum link = ProjectCurriculum.link(orgId, projectId, curriculumVersionId, nextSequenceNo, actorUserId);
        return projectCurriculumRepository.save(link);
    }

    // ── 9차 R4: 삭제 ────────────────────────────────────────────────────────

    /**
     * 교안 연결 해제. 변경 모달이 체크박스로 교안을 켜고 끄는데 끄는 쪽이 없어
     * <b>한 번 잘못 붙이면 되돌릴 수 없었다.</b>
     *
     * <p>연결 행은 이력이 아니라 현재 상태(project_curriculum에 소프트 삭제 컬럼이 없다)라 실제로 지운다.
     * 붙였다 뗀 기록이 필요해지면 그때 이력 컬럼을 추가한다.
     */
    @Override
    @Transactional
    public void unlinkCurriculum(UUID projectId, UUID orgId, UUID projectCurriculumId, UUID actorUserId) {
        findProject(projectId, orgId);

        ProjectCurriculum link = projectCurriculumRepository.findById(projectCurriculumId)
                .filter(candidate -> candidate.getProjectId().equals(projectId)
                        && candidate.getOrgId().equals(orgId))
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.CURRICULUM_LINK_NOT_FOUND,
                        "이 프로젝트의 교안 연결이 아닙니다: " + projectCurriculumId));

        if (projectDependencyRepository.hasConfirmedConceptsFromCurriculum(projectId, link.getCurriculumVersionId())) {
            throw new ApiException(ProjectExecutionErrorCode.CURRICULUM_IN_USE_BY_CONCEPTS,
                    "이 교안에서 확정된 검증 개념이 있어 연결을 해제할 수 없습니다. 검증 개념을 먼저 다시 확정해 주세요.");
        }

        projectCurriculumRepository.delete(link);
    }

    /**
     * 회차 삭제. 소프트 삭제라 요구사항·검증개념·교안 연결은 그대로 두고 목록·조회에서만 빠진다 —
     * 되살릴 일이 생겼을 때 되돌릴 수 있고, 과거 이력이 가리키는 대상이 사라지지 않는다.
     *
     * <p>삭제 가능 여부는 <b>서버가 판정한다.</b> 화면도 {@code rules.ts}의 {@code canDelete}로 막지만
     * 클라이언트 검증만 있으면 우회되고, 그때 사라지는 것은 학생이 실제로 한 제출·분석·응시다.
     */
    @Override
    @Transactional
    public void deleteProject(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = findProject(projectId, orgId);

        if (projectDependencyRepository.hasSubmissions(projectId)) {
            throw new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_DELETABLE,
                    "이 회차에는 이미 제출이 있어 삭제할 수 없습니다.");
        }
        if (projectDependencyRepository.hasAssessmentAttempts(projectId)) {
            throw new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_DELETABLE,
                    "이 회차에는 이미 응시 기록이 있어 삭제할 수 없습니다.");
        }

        project.softDelete(actorUserId);
    }
}