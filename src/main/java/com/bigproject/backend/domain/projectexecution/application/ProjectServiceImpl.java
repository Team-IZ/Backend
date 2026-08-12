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
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
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
        return findRoundLabelsByTeaches(List.of(teachesId), orgId).getOrDefault(teachesId, List.of());
    }

    @Override
    public Map<UUID, List<String>> findRoundLabelsByTeaches(Collection<UUID> teachesIds, UUID orgId) {
        if (teachesIds.isEmpty()) {
            return Map.of();
        }

        List<ProjectVerificationConcept> concepts =
                verificationConceptRepository.findByTeachesIdIn(Set.copyOf(teachesIds));
        if (concepts.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UUID> projectIdBySetId = verificationConceptSetRepository
                .findAllById(concepts.stream().map(ProjectVerificationConcept::getConceptSetId).collect(Collectors.toSet()))
                .stream()
                .filter(set -> set.getStatus() == ConceptSetStatus.ACTIVE)
                .collect(Collectors.toMap(ProjectVerificationConceptSet::getConceptSetId,
                        ProjectVerificationConceptSet::getProjectId));

        Map<UUID, String> labelByProjectId = resolveRoundLabels(Set.copyOf(projectIdBySetId.values()), orgId);

        Map<UUID, List<String>> result = new LinkedHashMap<>();
        for (ProjectVerificationConcept concept : concepts) {
            UUID projectId = projectIdBySetId.get(concept.getConceptSetId());
            if (projectId == null) {
                continue;
            }
            String label = labelByProjectId.get(projectId);
            if (label == null) {
                continue;
            }
            List<String> labels = result.computeIfAbsent(concept.getTeachesId(), key -> new ArrayList<>());
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        return result;
    }

    private Map<UUID, String> resolveRoundLabels(Collection<UUID> projectIds, UUID orgId) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }

        List<Project> projects = projectRepository.findByProjectIdInAndOrgIdAndDeletedAtIsNull(projectIds, orgId).stream()
                .filter(project -> project.getProjectCategory() == ProjectCategory.MINI_PROJECT)
                .toList();
        if (projects.isEmpty()) {
            return Map.of();
        }

        Set<UUID> cohortIds = projects.stream().map(Project::getCohortId).collect(Collectors.toSet());

        Map<UUID, String> cohortNameById = projectDependencyRepository.findCohortNames(cohortIds);

        Map<UUID, String> labelByProjectId = new HashMap<>();
        Map<UUID, List<Project>> miniProjectsByCohort = projectRepository
                .findByCohortIdInAndOrgIdAndProjectCategoryAndDeletedAtIsNullOrderBySequenceNoAsc(
                        cohortIds, orgId, ProjectCategory.MINI_PROJECT)
                .stream()
                .collect(Collectors.groupingBy(Project::getCohortId, LinkedHashMap::new, Collectors.toList()));

        miniProjectsByCohort.forEach((cohortId, ordered) -> {
            String cohortName = cohortNameById.get(cohortId);
            for (int index = 0; index < ordered.size(); index++) {
                String label = "미프 " + (index + 1) + "차";
                labelByProjectId.put(ordered.get(index).getProjectId(),
                        cohortName == null ? label : cohortName + " " + label);
            }
        });

        Map<UUID, String> result = new HashMap<>();
        for (Project project : projects) {
            String label = labelByProjectId.get(project.getProjectId());
            if (label != null) {
                result.put(project.getProjectId(), label);
            }
        }
        return result;
    }

    @Override
    public List<String> findRoundLabelsUsingCurriculum(UUID curriculumVersionId, UUID orgId) {
        return findProjectsUsingCurricula(List.of(curriculumVersionId), orgId).stream()
                .map(CurriculumUsingProject::roundLabel)
                .toList();
    }

    @Override
    public List<CurriculumUsingProject> findProjectsUsingCurricula(Collection<UUID> curriculumVersionIds, UUID orgId) {
        if (curriculumVersionIds.isEmpty()) {
            return List.of();
        }
        List<UUID> projectIds = projectCurriculumRepository
                .findAllByCurriculumVersionIdIn(Set.copyOf(curriculumVersionIds)).stream()
                .map(ProjectCurriculum::getProjectId)
                .distinct()
                .toList();
        if (projectIds.isEmpty()) {
            return List.of();
        }

        List<Project> projects = projectRepository.findByProjectIdInAndOrgIdAndDeletedAtIsNull(projectIds, orgId);
        if (projects.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> labelByProjectId = resolveRoundLabels(
                projects.stream().map(Project::getProjectId).toList(), orgId);
        Map<UUID, String> cohortNameById = projectDependencyRepository.findCohortNames(
                projects.stream().map(Project::getCohortId).collect(Collectors.toSet()));
        Map<UUID, Integer> attendedByProjectId = projectDependencyRepository.countAttendedByProject(
                projects.stream().map(Project::getProjectId).toList());

        Map<UUID, List<String>> conceptNamesByProjectId = findConfirmedConceptNames(
                projects.stream().map(Project::getProjectId).toList());

        return projects.stream()
                .sorted(Comparator.comparing(Project::getCohortId).thenComparing(Project::getSequenceNo))
                .map(project -> new CurriculumUsingProject(
                        project.getProjectId(),
                        project.getName(),
                        labelByProjectId.get(project.getProjectId()),
                        project.getCohortId(),
                        cohortNameById.get(project.getCohortId()),
                        attendedByProjectId.getOrDefault(project.getProjectId(), 0),
                        conceptNamesByProjectId.getOrDefault(project.getProjectId(), List.of())))
                .toList();
    }

    private Map<UUID, List<String>> findConfirmedConceptNames(Collection<UUID> projectIds) {
        List<ProjectVerificationConceptSet> activeSets =
                verificationConceptSetRepository.findByProjectIdInAndStatus(projectIds, ConceptSetStatus.ACTIVE);
        if (activeSets.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UUID> projectIdBySetId = activeSets.stream()
                .collect(Collectors.toMap(ProjectVerificationConceptSet::getConceptSetId,
                        ProjectVerificationConceptSet::getProjectId));

        List<ProjectVerificationConcept> concepts =
                verificationConceptRepository.findByConceptSetIdInOrderBySequenceNoAsc(projectIdBySetId.keySet());

        Map<UUID, String> nameByMappingId = mappingRepository
                .findAllById(concepts.stream().map(ProjectVerificationConcept::getSourceMappingId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(CurriculumTeachesMapping::getMappingId,
                        CurriculumTeachesMapping::getExtractedName));

        Map<UUID, List<String>> result = new LinkedHashMap<>();
        for (ProjectVerificationConcept concept : concepts) {
            UUID projectId = projectIdBySetId.get(concept.getConceptSetId());
            String name = nameByMappingId.get(concept.getSourceMappingId());
            if (projectId != null && name != null) {
                result.computeIfAbsent(projectId, key -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public Project createProject(UUID orgId, UUID cohortId, String name, ProjectCategory category,
                                 LocalDate startDate, LocalDate endDate, UUID actorUserId) {
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

    @Override
    public ProjectList findProjectList(UUID cohortId, UUID orgId, ProjectListCriteria criteria) {
        List<ProjectSummary> population = findProjects(cohortId, orgId).stream()
                .map(project -> summarize(project, orgId))
                .toList();
        return buildProjectList(population, orgId, criteria, null);
    }

    /**
     * 반(class) 하나가 담당하는 프로젝트 목록. team.class_id를 경유해 좁힌다 —
     * 이 반의 팀이 하나도 편성되지 않은 프로젝트는 결과에서 빠진다.
     */
    @Override
    public ProjectList findProjectListByClass(
            UUID classId, UUID orgId, ProjectListCriteria criteria, ProjectCategory category) {

        List<UUID> projectIds = projectDependencyRepository.findProjectIdsByClassId(classId, orgId);
        if (projectIds.isEmpty()) {
            return emptyProjectList();
        }

        List<ProjectSummary> population = projectRepository
                .findByProjectIdInAndOrgIdAndDeletedAtIsNull(projectIds, orgId).stream()
                .map(project -> summarize(project, orgId))
                .toList();
        return buildProjectList(population, orgId, criteria, category);
    }

    /** 팀이 하나도 편성되지 않은 반을 위한 빈 목록. 카운트 키는 전부 채우고 값만 0이다. */
    private ProjectList emptyProjectList() {
        Map<ProjectLifecycleStatus, Long> counts = new EnumMap<>(ProjectLifecycleStatus.class);
        for (ProjectLifecycleStatus status : ProjectLifecycleStatus.values()) {
            counts.put(status, 0L);
        }
        Map<ProjectReadiness, Long> readinessCounts = new EnumMap<>(ProjectReadiness.class);
        for (ProjectReadiness readiness : ProjectReadiness.values()) {
            readinessCounts.put(readiness, 0L);
        }
        return new ProjectList(List.of(), counts, readinessCounts);
    }

    /**
     * 카운트 계산 → 필터 → 정렬. population은 이미 조회 범위가 좁혀진 상태(기수 전체 또는 반 제한)다.
     * category는 반 기준 조회에서만 쓰이는 축이다.
     */
    private ProjectList buildProjectList(
            List<ProjectSummary> population, UUID orgId, ProjectListCriteria criteria, ProjectCategory category) {

        Map<ProjectLifecycleStatus, Long> counts = new EnumMap<>(ProjectLifecycleStatus.class);
        for (ProjectLifecycleStatus status : ProjectLifecycleStatus.values()) {
            counts.put(status, 0L);
        }
        population.forEach(summary -> counts.merge(summary.project().getLifecycleStatus(), 1L, Long::sum));

        Map<ProjectReadiness, Long> readinessCounts = new EnumMap<>(ProjectReadiness.class);
        for (ProjectReadiness readiness : ProjectReadiness.values()) {
            readinessCounts.put(readiness, 0L);
        }
        population.stream()
                .filter(summary -> summary.project().getLifecycleStatus() == ProjectLifecycleStatus.PLANNED)
                .forEach(summary -> readinessCounts.merge(summary.readiness(), 1L, Long::sum));

        String search = criteria.search() == null || criteria.search().isBlank()
                ? null
                : criteria.search().trim().toLowerCase(Locale.ROOT);

        List<ProjectSummary> filtered = new ArrayList<>();
        for (ProjectSummary summary : population) {
            Project project = summary.project();
            if (criteria.status() != null && project.getLifecycleStatus() != criteria.status()) {
                continue;
            }
            if (category != null && project.getProjectCategory() != category) {
                continue;
            }
            if (search != null && !project.getName().toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }
            if (criteria.curriculumId() != null && !usesCurriculum(project.getProjectId(), orgId, criteria.curriculumId())) {
                continue;
            }
            filtered.add(summary);
        }

        filtered.sort(comparatorFor(criteria.sort() == null ? ProjectListSort.READINESS : criteria.sort()));
        return new ProjectList(List.copyOf(filtered), counts, readinessCounts);
    }

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

    private Comparator<ProjectSummary> comparatorFor(ProjectListSort sort) {
        return switch (sort) {
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

    private List<ConfirmedConcept> findConfirmedConcepts(UUID projectId) {
        return verificationConceptSetRepository.findByProjectIdAndStatus(projectId, ConceptSetStatus.ACTIVE)
                .map(set -> verificationConceptRepository
                        .findByConceptSetIdOrderBySequenceNoAsc(set.getConceptSetId()).stream()
                        .map(this::toConfirmedConcept)
                        .toList())
                .orElseGet(List::of);
    }

    private ConfirmedConcept toConfirmedConcept(ProjectVerificationConcept concept) {
        CurriculumTeachesMapping mapping = mappingRepository.findById(concept.getSourceMappingId())
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.CONCEPT_SOURCE_MAPPING_MISSING,
                        "확정 개념의 출처 매핑을 찾을 수 없습니다: mappingId=" + concept.getSourceMappingId()));
        return new ConfirmedConcept(
                mapping.getMappingId(),
                concept.getTeachesId(),
                mapping.getExtractedName(),
                mapping.getVersionId(),
                mapping.getPageStart(),
                mapping.getPageEnd());
    }

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

        List<CurriculumTeachesMapping> mappings = resolveMappings(mappingIds);

        Optional<ProjectVerificationConceptSet> activeSet =
                verificationConceptSetRepository.findByProjectIdAndStatus(projectId, ConceptSetStatus.ACTIVE);

        if (activeSet.isPresent() && isSameAsConfirmed(activeSet.get(), mappingIds)) {
            return;
        }

        activeSet.ifPresent(existing -> existing.supersede(OffsetDateTime.now()));

        verificationConceptSetRepository.flush();

        ProjectVerificationConceptSet newSet = ProjectVerificationConceptSet.activate(
                projectId, orgId, verificationConceptSetRepository.findMaxVersionNo(projectId) + 1,
                actorUserId, "검증개념 확정");
        verificationConceptSetRepository.save(newSet);

        int sequenceNo = 1;
        for (CurriculumTeachesMapping mapping : mappings) {
            ProjectVerificationConcept concept = ProjectVerificationConcept.of(
                    newSet.getConceptSetId(), orgId, mapping.getTeachesId(), mapping.getMappingId(), sequenceNo++);
            verificationConceptRepository.save(concept);
        }
    }

    private List<CurriculumTeachesMapping> resolveMappings(List<UUID> mappingIds) {
        List<CurriculumTeachesMapping> mappings = new ArrayList<>();
        Map<UUID, CurriculumTeachesMapping> seenByTeachesId = new LinkedHashMap<>();

        for (UUID mappingId : mappingIds) {
            CurriculumTeachesMapping mapping = mappingRepository.findById(mappingId)
                    .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.CONCEPT_MAPPING_NOT_FOUND,
                            "존재하지 않는 매핑입니다: " + mappingId));

            CurriculumTeachesMapping duplicate = seenByTeachesId.putIfAbsent(mapping.getTeachesId(), mapping);
            if (duplicate != null) {
                throw new ApiException(ProjectExecutionErrorCode.CONCEPT_DUPLICATED,
                        "같은 개념을 두 번 확정할 수 없습니다: " + mapping.getExtractedName());
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    private boolean isSameAsConfirmed(ProjectVerificationConceptSet activeSet, List<UUID> mappingIds) {
        List<UUID> confirmed = verificationConceptRepository
                .findByConceptSetIdOrderBySequenceNoAsc(activeSet.getConceptSetId()).stream()
                .map(ProjectVerificationConcept::getSourceMappingId)
                .toList();
        return confirmed.equals(mappingIds);
    }

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