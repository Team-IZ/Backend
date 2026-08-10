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

    /**
     * 11차 R1·R5. 한 건씩 부르던 것을 <b>쿼리 4건</b>으로 접었다.
     *
     * <p>예전 경로는 개념마다 세트를 {@code findById}로 읽고, 세트마다
     * {@link #resolveMiniProjectRoundLabel}이 프로젝트 한 건과 <b>그 기수의 미니프로젝트 전량</b>을
     * 다시 읽었다. 같은 프로젝트·같은 기수를 몇 번이고 다시 읽는 구조라 항목이 늘수록 제곱으로 늘었다.
     */
    @Override
    public Map<UUID, List<String>> findRoundLabelsByTeaches(Collection<UUID> teachesIds, UUID orgId) {
        if (teachesIds.isEmpty()) {
            return Map.of();
        }

        // ① 개념 행 전부
        List<ProjectVerificationConcept> concepts =
                verificationConceptRepository.findByTeachesIdIn(Set.copyOf(teachesIds));
        if (concepts.isEmpty()) {
            return Map.of();
        }

        // ② 세트 전부 → ACTIVE 인 것만 projectId 로
        Map<UUID, UUID> projectIdBySetId = verificationConceptSetRepository
                .findAllById(concepts.stream().map(ProjectVerificationConcept::getConceptSetId).collect(Collectors.toSet()))
                .stream()
                .filter(set -> set.getStatus() == ConceptSetStatus.ACTIVE)
                .collect(Collectors.toMap(ProjectVerificationConceptSet::getConceptSetId,
                        ProjectVerificationConceptSet::getProjectId));

        // ③ 프로젝트 전부 → ④ 기수별 미니프로젝트 순서로 라벨 표를 한 번에 만든다
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
            // 같은 라벨이 여러 기수에서 나오므로 중복을 걸러 넣는다(11차 R5). 순서는 첫 등장 순이다.
            List<String> labels = result.computeIfAbsent(concept.getTeachesId(), key -> new ArrayList<>());
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        return result;
    }

    /**
     * 프로젝트 ID 여러 개 → {@code 9기 미프 3차} 라벨. 기수별 미니프로젝트 목록을 <b>기수당 한 번만</b> 읽는다.
     *
     * <p>라벨에 기수를 붙이는 이유는 11차 R5다 — 교안 하나가 4개 기수에 쓰이면 `미프 1차`가 4번 나오는데,
     * 기수를 떼고 중복만 지우면 서로 다른 기수의 회차가 한 줄로 합쳐진다.
     *
     * <p>미니프로젝트가 아니거나 삭제된 프로젝트는 <b>결과에서 빠진다</b> — 예외를 던지지 않는다.
     * 목록을 그리다 라벨 하나 때문에 화면 전체가 실패하면 안 된다.
     */
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

        // 기수별 미니프로젝트를 sequence_no 순으로 한 번에 읽어 회차 번호를 매긴다.
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

        // 요청한 프로젝트만 남긴다(같은 기수의 다른 회차까지 표에 들어가 있다).
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

    /**
     * 11차 R3. 이름 배열만 주던 것을 회차 객체로 바꿨다 — 재분석 경고가
     * "연결된 회차가 있으면 무조건"에서 "응시가 시작된 회차만"으로 좁혀질 수 있어야 한다.
     */
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

        // 회차가 지금 쓰고 있는(ACTIVE) 확정 개념 이름. 재분석하면 위치가 어긋날 개념들이다.
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

    /** 프로젝트별 활성 확정 개념 이름. 세트·개념·매핑을 각각 한 번씩만 읽는다. */
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
        // 모집단 전체를 먼저 요약한다. readiness 개수(10차 Q1)가 걸러지지 않은 모집단 기준이라
        // 필터를 통과한 것만 요약해서는 만들 수 없다.
        List<ProjectSummary> population = findProjects(cohortId, orgId).stream()
                .map(project -> summarize(project, orgId))
                .toList();

        // 상태별 개수는 필터를 적용하지 않은 모집단이다 — 상태 칩이 자기 자신을 필터링하면
        // 언제나 자기 개수만 남아 다른 칩이 0이 된다. 0인 상태도 키를 채운다(키가 빠지는 것과 다르다).
        Map<ProjectLifecycleStatus, Long> counts = new EnumMap<>(ProjectLifecycleStatus.class);
        for (ProjectLifecycleStatus status : ProjectLifecycleStatus.values()) {
            counts.put(status, 0L);
        }
        population.forEach(summary -> counts.merge(summary.project().getLifecycleStatus(), 1L, Long::sum));

        // 화면의 상태 필터는 4값인데 status는 3값이라 `준비 중`·`준비됨`만 개수를 못 쓰고 있었다(10차 Q1).
        // PLANNED만 준비 상태로 다시 가른다 — RUNNING·CLOSED에는 readiness가 의미 없다
        // (화면도 `status === 'PLANNED' ? readiness : status`로 겹친다).
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
     * source_mapping_id로 되짚어 채운다.
     *
     * <p>예전에는 매핑이 없으면 이름·페이지를 null로 채워 내보냈는데, 그 경로가 응답 타입을
     * nullable로 만들어 프론트가 "확정됐는데 이름이 없는 개념"을 그려야 하는지 묻게 됐다(10차 Q2).
     * FK가 ON DELETE RESTRICT라 <b>매핑은 참조되는 동안 지워지지 않으므로</b> 그런 개념은 생기지 않는다 —
     * 없다면 DB가 규약 밖에서 바뀐 것이라 조용히 넘기지 않는다.
     */
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

    /**
     * 검증 개념 확정. <b>몇 번을 불러도 같은 결과가 된다</b>(10차 R1).
     *
     * <p>이전에는 최초 확정만 되고 교체가 전부 409였다. 원인은 flush 순서였다 —
     * Hibernate는 flush 시 INSERT를 UPDATE보다 먼저 실행하므로, 기존 세트를 SUPERSEDED로
     * 바꾸는 UPDATE보다 새 ACTIVE 세트의 INSERT가 먼저 나가 한 프로젝트에 ACTIVE 행이 순간
     * 2건이 됐고, 부분 유니크 인덱스
     * {@code uq_proj_verif_concept_set_active (project_id) WHERE status='ACTIVE' AND effective_to IS NULL}가
     * 이를 막았다. 그래서 <b>바꾸는 것이 없는 재확정조차</b> 실패했다.
     *
     * <p>고친 방식은 세 가지다.
     * <ol>
     *   <li>같은 세트를 다시 확정하면 <b>아무 일도 하지 않는다</b> — 버전 이력을 헛되이 쌓지 않는다.</li>
     *   <li>바뀌었으면 기존 세트를 닫는 UPDATE를 <b>먼저 flush</b>한 뒤 새 세트를 넣는다.</li>
     *   <li>버전 번호를 활성 세트 기준이 아니라 <b>최대값 + 1</b>로 채번한다.</li>
     * </ol>
     */
    @Override
    @Transactional
    public void confirmConcepts(UUID projectId, UUID orgId, List<UUID> mappingIds, UUID actorUserId) {
        findProject(projectId, orgId);

        List<CurriculumTeachesMapping> mappings = resolveMappings(mappingIds);

        Optional<ProjectVerificationConceptSet> activeSet =
                verificationConceptSetRepository.findByProjectIdAndStatus(projectId, ConceptSetStatus.ACTIVE);

        // 같은 세트를 그대로 다시 보내는 것은 아무것도 바꾸지 않는 요청이다. 새 버전을 만들지 않고 끝낸다.
        // 이 판정을 화면에 두면 "무엇이 바뀐 것인가"라는 같은 규칙이 화면과 서버 두 곳에 생긴다(10차 R1).
        if (activeSet.isPresent() && isSameAsConfirmed(activeSet.get(), mappingIds)) {
            return;
        }

        activeSet.ifPresent(existing -> existing.supersede(OffsetDateTime.now()));

        // 닫는 UPDATE를 새 행 INSERT보다 먼저 DB로 보낸다. 이 flush가 없으면 위 javadoc의 409가 난다.
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

    /**
     * 요청의 매핑 ID를 원장 행으로 바꾼다. 쓰기를 시작하기 <b>전에</b> 전부 확인해서,
     * 절반만 반영되고 실패하는 상태를 만들지 않는다.
     *
     * <p>같은 개념(teaches)이 두 번 들어오면 여기서 400으로 끊는다. 교안 두 벌이 같은 개념을
     * 가르치면 후보 목록에 다른 매핑으로 두 줄 나오기 때문에 실제로 고를 수 있는 조합이고,
     * 그대로 두면 {@code uq_..._concept_set_id_teaches_id}에 걸려 정체 모를 409가 된다.
     */
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

    /** 확정된 개념의 매핑 ID가 요청과 <b>순서까지</b> 같은가. 순서는 화면이 칩을 나열하는 순서라 의미가 있다. */
    private boolean isSameAsConfirmed(ProjectVerificationConceptSet activeSet, List<UUID> mappingIds) {
        List<UUID> confirmed = verificationConceptRepository
                .findByConceptSetIdOrderBySequenceNoAsc(activeSet.getConceptSetId()).stream()
                .map(ProjectVerificationConcept::getSourceMappingId)
                .toList();
        return confirmed.equals(mappingIds);
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