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
import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository.RoundSchedule;
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
import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.global.exception.ApiException;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
     * 30차 Q2 — 기수의 프로젝트 전부를 한 번에 읽고, 그 프로젝트들이 건 교안 링크를 한 번에 읽는다.
     * 회차마다 링크를 부르면 기수당 회차 수만큼 조회가 붙는다(그것이 프론트가 피하려던 모양이다).
     */
    @Override
    public List<CohortCurriculumLink> findCurriculumLinksInCohort(UUID cohortId, UUID orgId) {
        List<Project> projects = projectRepository
                .findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByCreatedAtDesc(cohortId, orgId);
        if (projects.isEmpty()) {
            return List.of();
        }
        Map<UUID, Project> projectById = projects.stream()
                .collect(Collectors.toMap(Project::getProjectId, project -> project));

        return projectCurriculumRepository
                .findAllByProjectIdInAndOrgId(projectById.keySet(), orgId).stream()
                .map(link -> {
                    Project project = projectById.get(link.getProjectId());
                    return new CohortCurriculumLink(link.getCurriculumVersionId(),
                            project.getProjectId(), project.getName(), project.getSequenceNo());
                })
                // 화면이 `미프 1차 · 2차 · 3차`를 위에서 아래로 그리는 순서와 같다.
                .sorted(Comparator.comparingInt(CohortCurriculumLink::sequenceNo)
                        .thenComparing(CohortCurriculumLink::projectName))
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
                                 LocalDate startDate, LocalDate endDate, Instant submissionDueAt,
                                 UUID actorUserId) {
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

        Project saved = projectRepository.save(project);

        // 22차 R5·R6 — 회차를 함께 만든다. 여태 만들지 않아서 화면으로 만든 프로젝트는 회차가 없는
        // 채로 남았고, 현황 탭이 회차를 못 찾았으며 제출 마감을 저장할 자리도 없었다.
        // 같은 트랜잭션이라 회차 INSERT가 실패하면 프로젝트도 함께 롤백된다 — 회차 없는 프로젝트를
        // 다시 만들지 않기 위해서다.
        projectDependencyRepository.createAssessmentRound(
                saved.getProjectId(), orgId, cohortId, name,
                submissionDueAt != null ? submissionDueAt : deriveSubmissionDueAt(endDate),
                actorUserId);

        return saved;
    }

    /**
     * 마감을 안 보냈을 때의 파생 규칙 — <b>종료일의 23:59 KST</b>.
     *
     * <p>기존 회차들이 그 규칙으로 들어가 있어 화면이 이미 그렇게 읽고 있다. 값을 만들어 넣지 않고
     * 비워 둘 수는 없다 — {@code submission_due_at}이 DB에서 NOT NULL이다.
     *
     * <p>여기서 파생한 뒤에는 {@code endDate}와 <b>다시 연결되지 않는다.</b> 기간을 늘려도 마감은
     * 움직이지 않으며, 함께 옮기려면 일정 수정에서 마감을 같이 보내야 한다(18차 R5의 판단 그대로).
     * 학생에게 이미 알린 마감이 조용히 바뀌는 것을 막기 위해서다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static Instant deriveSubmissionDueAt(LocalDate endDate) {
        return endDate.atTime(23, 59).atZone(KST).toInstant();
    }

    @Override
    public List<Project> findProjects(UUID cohortId, UUID orgId) {
        return projectRepository.findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByCreatedAtDesc(cohortId, orgId);
    }

    /**
     * 「이번 회차」 판정(15차 R1). <b>규칙을 서버가 갖는다.</b>
     *
     * <ol>
     *   <li>{@code RUNNING}이 있으면 그것. 여럿이면 <b>가장 늦게 시작한</b> 것 — 회차가 겹쳐 열린
     *       상태에서는 나중에 연 쪽이 지금 굴러가는 것이다</li>
     *   <li>없으면 <b>가장 이른 {@code PLANNED}</b> — 다음에 열릴 회차가 지금의 관심사다</li>
     *   <li>그것도 없으면 <b>마지막 회차</b>(전부 {@code CLOSED}인 기수) — 끝난 기수에서도
     *       화면이 마지막 결과를 그려야 한다</li>
     * </ol>
     *
     * <p>③이 {@code CLOSED}를 돌려주므로 <b>호출부는 상태를 보고 그려야 한다.</b> "진행 중"이라고
     * 단정하지 않는다 — 응답에 {@code status}가 함께 나가는 이유다.
     *
     * <p>정렬 키로 {@code sequenceNo}를 앞에 두고 {@code startDate}를 뒤에 둔다. 정의서가
     * {@code sequence_no}를 "기수 내 전체 프로젝트 운영 순서"로 정의하므로 그것이 권위 축이고,
     * 날짜는 비어 있을 수 있어 보조로만 쓴다.
     */
    @Override
    public Optional<ProjectSummary> findCurrentProject(UUID cohortId, UUID orgId) {
        // 22차 R7·R8 — 204(회차가 없다)와 404(그런 기수가 없다)를 가른다. 여태 둘 다 204라
        // 지워진 기수를 열어도 화면이 「이번 회차 없음」으로 그렸다.
        requireCohort(cohortId, orgId);

        // 22차 R10 ⓐ — 목록을 한 번 읽어 「이번 회차」와 전체 회차 수를 함께 얻는다. 화면의
        // `3차 / 6회`에서 분모가 이 값이고, 그것 하나가 없어서 대시보드가 이 API를 못 쓰고
        // 목록을 계속 부르고 있었다. 여기서 세면 조회가 늘지 않는다 — 어차피 읽던 목록이다.
        List<Project> projects = findProjects(cohortId, orgId);

        // 요약은 고른 하나에만 매긴다. 목록이 느렸던 이유가 모집단 전체를 요약한 것이라
        // (summarizeAll 주석), 여기서 같은 실수를 하면 이 API를 만든 뜻이 사라진다.
        return chooseCurrent(projects)
                .map(chosen -> summarizeAll(List.of(chosen), orgId, projects.size()).get(0));
    }

    /**
     * 「이번 회차」 판정의 <b>유일한 구현</b>이다. 요약이 필요한 쪽은 {@link #findCurrentProject}가,
     * 프로젝트만 필요한 쪽(교육생 명단의 기본 회차)은 이것을 쓴다 — 규칙이 두 곳으로 갈리면
     * 같은 기수를 두고 화면마다 다른 차수를 말하게 된다.
     */
    @Override
    public Optional<Project> resolveCurrentProject(UUID cohortId, UUID orgId) {
        return chooseCurrent(findProjects(cohortId, orgId));
    }

    /**
     * 판정 규칙 자체. 이미 목록을 손에 든 호출부가 <b>다시 읽지 않고</b> 쓰도록 떼어 둔다 —
     * {@link #findCurrentProject}는 전체 회차 수({@code totalRounds})를 세느라 어차피 목록이 필요하다.
     */
    private Optional<Project> chooseCurrent(List<Project> projects) {
        if (projects.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(projects.stream()
                .filter(project -> project.getLifecycleStatus() == ProjectLifecycleStatus.RUNNING)
                .max(ORDER)
                .or(() -> projects.stream()
                        .filter(project -> project.getLifecycleStatus() == ProjectLifecycleStatus.PLANNED)
                        .min(ORDER))
                .orElseGet(() -> projects.stream().max(ORDER).orElseThrow()));
    }

    /** 기수 안 운영 순서. {@code sequence_no}가 권위 축이고 날짜는 비어 있을 수 있어 보조다. */
    private static final Comparator<Project> ORDER =
            Comparator.<Project, Integer>comparing(Project::getSequenceNo,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Project::getStartDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Project::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Override
    public Project findProject(UUID projectId, UUID orgId) {
        return projectRepository.findByProjectIdAndOrgIdAndDeletedAtIsNull(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));
    }

    @Override
    @Transactional
    public Project updateSchedule(UUID projectId, UUID orgId, LocalDate startDate, LocalDate endDate, UUID actorUserId) {
        return updateSchedule(projectId, orgId, startDate, endDate, null, actorUserId);
    }

    @Override
    @Transactional
    public Project updateSchedule(UUID projectId, UUID orgId, LocalDate startDate, LocalDate endDate,
            Instant submissionDueAt, UUID actorUserId) {

        Project project = findProject(projectId, orgId);
        project.updateSchedule(startDate, endDate, actorUserId);

        // 18차 R5 — 마감은 회차(project_assessment_round)에 있고 기간은 프로젝트에 있다.
        // 둘을 한 트랜잭션에서 바꾸되, 보내지 않았으면 마감은 그대로 둔다.
        if (submissionDueAt != null) {
            projectDependencyRepository.updateSubmissionDueAt(projectId, orgId, submissionDueAt);
        }
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
                project, curricula.size(), concepts.size(), candidateCount,
                List.of(), List.of(),
                // 22차 R5·R9 — 상세가 실제 마감과 회차 시각을 그리는 근거다.
                projectDependencyRepository.findRoundSchedules(List.of(projectId)).get(projectId));

        return new ProjectDetail(summary, curricula, concepts, requirementTitles);
    }

    @Override
    public List<ProjectSummary> findProjectSummaries(UUID cohortId, UUID orgId) {
        requireCohort(cohortId, orgId);
        List<Project> projects = findProjects(cohortId, orgId);
        return summarizeAll(projects, orgId, projects.size());
    }

    /**
     * 22차 R7 — 「이 기수엔 없다」와 「그런 기수가 없다」를 가른다.
     *
     * <p>{@code ?cohort=}가 붙은 링크는 남이 보낸 것이거나 그 사이 지워진 기수일 수 있다.
     * 둘 다 빈 화면이 되면 운영자는 <b>아직 아무것도 안 만든 기수</b>로 읽는다.
     *
     * <p>{@code GET /cohorts/{id}/projects/current}는 이미 없으면 204라 이 판정과 어긋나지 않는다 —
     * 그쪽은 "지금 진행 중인 회차가 없다"가 정상 상태라 목록과 뜻이 다르다.
     */
    private void requireCohort(UUID cohortId, UUID orgId) {
        if (!projectDependencyRepository.cohortExists(cohortId, orgId)) {
            throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
        }
    }

    /**
     * 여러 프로젝트를 <b>고정 개수의 쿼리</b>로 요약한다(15차 R1).
     *
     * <p>종전에는 {@link #summarize}를 프로젝트마다 불렀고 그 안이 다시 교안 버전마다 후보를 세어,
     * 회차 목록 한 번에 조회가 <b>회차 수 × (3 + 교안 수)</b>만큼 나갔다. 회차 7개 · 교안 2개면
     * 35건이다. 프론트 실측에서 이 목록이 요청과 무관하게 4~5초로 <b>일정했던</b> 이유가 이것이다 —
     * 느린 것은 집계 한 방이 아니라 왕복 수였고, 그래서 {@code ?status=RUNNING}으로 1건만 남겨도
     * 시간이 줄지 않았다({@link #findProjectList}가 필터 <b>전</b> 모집단 전체를 요약하기 때문).
     *
     * <p>지금은 4건이다 — 교안 연결 · 개념 세트 · 개념 수 · 후보 수.
     */
    private List<ProjectSummary> summarizeAll(List<Project> projects, UUID orgId, int totalRounds) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<UUID> projectIds = projects.stream().map(Project::getProjectId).toList();

        // ① 교안 연결을 전량 읽어 프로젝트별로 나눈다. 개수(curriculumCount)와 후보 집계의
        //    대상 버전 목록을 이 한 번의 조회가 함께 준다.
        Map<UUID, List<ProjectCurriculum>> linksByProject = projectCurriculumRepository
                .findAllByProjectIdInAndOrgId(projectIds, orgId).stream()
                .collect(Collectors.groupingBy(ProjectCurriculum::getProjectId));

        // ② 활성 개념 세트 → ③ 세트별 개념 수. 둘 다 11차에서 만들어 둔 일괄 조회를 쓴다.
        Map<UUID, UUID> conceptSetByProject = verificationConceptSetRepository
                .findByProjectIdInAndStatus(projectIds, ConceptSetStatus.ACTIVE).stream()
                .collect(Collectors.toMap(
                        ProjectVerificationConceptSet::getProjectId,
                        ProjectVerificationConceptSet::getConceptSetId,
                        (first, second) -> first));

        // ③ 세트별 개념. 개수만 세던 것을 목록까지 들고 있게 바꿨다(18차 R3) — 이름을 만들려면
        //    어차피 같은 행이 필요하고, 조회를 늘리지 않는다.
        Map<UUID, List<ProjectVerificationConcept>> conceptsBySet = conceptSetByProject.isEmpty()
                ? Map.of()
                : verificationConceptRepository
                        .findByConceptSetIdInOrderBySequenceNoAsc(conceptSetByProject.values()).stream()
                        .collect(Collectors.groupingBy(
                                ProjectVerificationConcept::getConceptSetId,
                                LinkedHashMap::new, Collectors.toList()));

        // ④ 후보 수를 교안 버전 단위로 한 번에 센다. GROUP BY라 후보가 0건인 버전은 행이 없다.
        Map<UUID, Long> candidateCountByVersion = candidateCountsByVersion(linksByProject, orgId);

        // ⑤⑥ 이름 두 벌(18차 R3). 목록의 `교안 1개`·`3건 확정`만으로는 교안 필터가 먹었는지
        //     확인할 수 없고, 개념도 "무엇을 확정했는지"가 곧 그 회차의 정체다.
        Map<UUID, String> curriculumNameByVersion = curriculumNamesByVersion(linksByProject);
        Map<UUID, String> conceptNameByMapping = conceptNamesByMapping(conceptsBySet);

        // ⑦ 회차 시각(22차 R5·R9). 목록·상세가 endDate를 마감이라고 그리던 것을 끝낸다.
        //    프로젝트 수와 무관하게 조회 1건이라 위 규칙(고정 개수의 쿼리)을 깨지 않는다.
        Map<UUID, RoundSchedule> scheduleByProject = projectDependencyRepository
                .findRoundSchedules(projectIds);

        List<ProjectSummary> summaries = new ArrayList<>(projects.size());
        for (Project project : projects) {
            List<ProjectCurriculum> links = linksByProject.getOrDefault(project.getProjectId(), List.of());
            UUID conceptSetId = conceptSetByProject.get(project.getProjectId());
            List<ProjectVerificationConcept> concepts = conceptSetId == null
                    ? List.of()
                    : conceptsBySet.getOrDefault(conceptSetId, List.of());

            long candidateCount = 0;
            for (ProjectCurriculum link : links) {
                candidateCount += candidateCountByVersion.getOrDefault(link.getCurriculumVersionId(), 0L);
            }

            summaries.add(new ProjectSummary(
                    project,
                    links.size(),
                    concepts.size(),
                    Math.toIntExact(candidateCount),
                    // 이름을 못 찾은 항목은 조용히 빠진다 — 목록의 표시용 값이라 여기서 터뜨리면
                    // 회차 하나 때문에 목록 전체가 안 열린다. 개수는 원장 그대로라 숫자는 맞는다.
                    links.stream()
                            .map(link -> curriculumNameByVersion.get(link.getCurriculumVersionId()))
                            .filter(Objects::nonNull)
                            .toList(),
                    concepts.stream()
                            .map(concept -> conceptNameByMapping.get(concept.getSourceMappingId()))
                            .filter(Objects::nonNull)
                            .toList(),
                    // 회차를 아직 만들지 않은 프로젝트는 키가 없다 — 22차 이전에 만들어진 것들이다.
                    scheduleByProject.get(project.getProjectId()),
                    totalRounds));
        }
        return summaries;
    }

    /** 연결된 교안 버전의 파일명을 한 번에. 화면의 `교안` 열이 이 값을 그린다. */
    private Map<UUID, String> curriculumNamesByVersion(
            Map<UUID, List<ProjectCurriculum>> linksByProject) {

        Set<UUID> versionIds = linksByProject.values().stream()
                .flatMap(List::stream)
                .map(ProjectCurriculum::getCurriculumVersionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (CurriculumVersion version : curriculumVersionRepository.findAllById(versionIds)) {
            names.put(version.getVersionId(), version.getOriginalFileName());
        }
        return names;
    }

    /**
     * 확정 개념의 이름을 한 번에.
     *
     * <p>이름은 개념 행이 아니라 <b>출처 매핑</b>에 있다({@code source_mapping_id}) —
     * {@link #toConfirmedConcept}가 상세에서 쓰는 것과 같은 원장이라 두 화면이 다른 이름을
     * 말하지 않는다.
     */
    private Map<UUID, String> conceptNamesByMapping(
            Map<UUID, List<ProjectVerificationConcept>> conceptsBySet) {

        Set<UUID> mappingIds = conceptsBySet.values().stream()
                .flatMap(List::stream)
                .map(ProjectVerificationConcept::getSourceMappingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (mappingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (CurriculumTeachesMapping mapping : mappingRepository.findAllById(mappingIds)) {
            names.put(mapping.getMappingId(), mapping.getExtractedName());
        }
        return names;
    }

    /** 연결된 교안 버전 전체의 후보 수를 한 번에. 연결이 없으면 조회하지 않는다. */
    private Map<UUID, Long> candidateCountsByVersion(
            Map<UUID, List<ProjectCurriculum>> linksByProject, UUID orgId) {

        Set<UUID> versionIds = linksByProject.values().stream()
                .flatMap(List::stream)
                .map(ProjectCurriculum::getCurriculumVersionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (versionIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : mappingRepository.countActiveCandidatesByVersionIds(
                versionIds, orgId, MappingStatus.ACTIVE)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * 목록 한 벌 + 필터와 무관한 상태별 개수(19차 — classId 필터 흡수).
     *
     * <p>{@code criteria.classId()}가 있으면 그 반의 팀이 편성된 프로젝트로 모집단을 좁히고,
     * 없으면 {@code cohortId} 기수 전체가 모집단이다. 반과 기수를 동시에 좁힐 이유가 없어
     * 정확히 하나만 쓴다 — 반이 정해지면 그 반이 속한 기수는 이미 정해져 있다.
     */
    /**
     * 목록 한 벌 + 필터와 무관한 상태별 개수(19차 — classId 필터 흡수).
     *
     * <p>{@code criteria.classId()}가 있으면 그 반들의 팀이 편성된 프로젝트로 모집단을 좁히고,
     * 없으면 {@code cohortId} 기수 전체가 모집단이다. 매니저가 담당하는 반은 보통 1~3개라
     * 반마다 한 번씩 조회해 합치는 것으로 충분하다 — 반 수가 수십 단위로 늘면 그때
     * {@link ProjectDependencyRepository}에 다건 조회를 추가한다.
     */
    @Override
    public ProjectList findProjectList(UUID cohortId, UUID orgId, ProjectListCriteria criteria) {
        List<ProjectSummary> population;
        if (criteria.classId() != null && !criteria.classId().isEmpty()) {
            Set<UUID> projectIds = new LinkedHashSet<>();
            for (UUID classId : criteria.classId()) {
                projectIds.addAll(projectDependencyRepository.findProjectIdsByClassId(classId, orgId));
            }
            if (projectIds.isEmpty()) {
                return emptyProjectList();
            }
            List<Project> projects = projectRepository
                    .findByProjectIdInAndOrgIdAndDeletedAtIsNull(projectIds, orgId);
            population = summarizeAll(projects, orgId, projects.size());
        } else {
            requireCohort(cohortId, orgId);
            List<Project> projects = findProjects(cohortId, orgId);
            population = summarizeAll(projects, orgId, projects.size());
        }
        return buildProjectList(population, orgId, criteria, criteria.category());
    }

    /**
     * @deprecated {@link #findProjectList}가 {@code criteria.classId()}로 같은 일을 한다
     *             (19차). 기존 {@code /classes/{classId}/...} 엔드포인트가 아직 이 시그니처로
     *             호출하므로, 그 엔드포인트를 걷어낼 때까지는 지우지 않고 새 메서드로 위임만 한다.
     */
    @Override
    @Deprecated(forRemoval = true)
    public ProjectList findProjectListByClass(
            UUID classId, UUID orgId, ProjectListCriteria criteria, ProjectCategory category) {
        ProjectListCriteria merged = new ProjectListCriteria(
                criteria.search(), criteria.curriculumId(), criteria.status(), criteria.sort(), List.of(classId), category);
        return findProjectList(null, orgId, merged);
    }

    /** 팀이 하나도 편성되지 않은 반을 위한 빈 목록. 카운트 키는 전부 채우고 값만 0이다. */
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

    // ── 9차 R3: 검색·필터·정렬·상태별 개수 ────────────────────────────────────

    /**
     * 카운트 계산 → 필터 → 정렬. population은 이미 조회 범위가 좁혀진 상태(기수 전체 또는 반 제한)다.
     * category는 반 기준 조회에서만 쓰이는 축이다.
     */
    private ProjectList buildProjectList(
            List<ProjectSummary> population, UUID orgId, ProjectListCriteria criteria, ProjectCategory category) {
        // 상태별 개수는 필터를 적용하지 않은 모집단이다 — 상태 칩이 자기 자신을 필터링하면
        // 언제나 자기 개수만 남아 다른 칩이 0이 된다. 0인 상태도 키를 채운다(키가 빠지는 것과 다르다).
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

        // 오늘을 한 번만 읽어 넘긴다 — 비교 중에 날짜가 바뀌면 정렬이 비일관해진다.
        filtered.sort(comparatorFor(
                criteria.sort() == null ? ProjectListSort.READINESS : criteria.sort(), LocalDate.now()));
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
     * 정렬 기준(18차 R4로 셋 다 다시 씀).
     *
     * <h2>종전에 셋이 사실상 같았다</h2>
     *
     * <p>세 정렬이 전부 <b>날짜 오름차순</b>으로만 동작해 결과가 거의 구분되지 않았고, 셋 다
     * 5개월 전에 끝난 회차를 맨 위에 놓았다. 목록의 목적은 <em>손댈 것이 남은 회차 찾기</em>인데
     * 가장 위 다섯 줄이 전부 "할 일 없음"이었다.
     *
     * <p>원인은 <b>오늘을 안 봤다</b>는 것이다. 날짜만 비교하면 지난 것과 남은 것이 한 줄에
     * 섞이고, 지난 것이 항상 더 작으므로 먼저 온다.
     *
     * <h2>공통 규칙 — 끝난 것은 뒤로</h2>
     *
     * <p>{@code CLOSED}는 <b>어떤 정렬에서도 마지막 그룹</b>이다. 지난 일은 목록의 목적과
     * 무관하고, 필요하면 상태 필터로 본다. 그 안에서는 최근에 끝난 것이 앞이다 — 되짚어 볼
     * 이유가 있다면 방금 끝난 회차이기 때문이다.
     *
     * @param today 기준선. 호출 시점의 날짜를 넘겨 정렬이 시각에 의존하지 않게 한다
     */
    // 인스턴스 상태를 쓰지 않는다. 정렬 규칙만 따로 테스트할 수 있도록 static·package-private다.
    static Comparator<ProjectSummary> comparatorFor(ProjectListSort sort, LocalDate today) {
        // 끝난 것을 뒤로 보내는 공통 1차 키. CLOSED면 1, 아니면 0이다.
        Comparator<ProjectSummary> closedLast =
                Comparator.comparingInt(summary -> isClosed(summary) ? 1 : 0);
        return switch (sort) {
            /*
             * 준비 필요 순 — "지금 손대야 하는 것".
             *
             * 준비도를 시간 축보다 앞에 둔다. PLANNED 중 빈 것이 있는 회차(PREP)가 가장
             * 급하고, 그 다음이 준비를 마친 PLANNED, 그 다음이 이미 굴러가는 RUNNING이다.
             * unreadyCount 는 배지(readiness)와 같은 규칙이라 순서와 배지가 어긋나지 않는다(9차 Q1).
             */
            case READINESS -> closedLast
                    .thenComparingInt(ProjectServiceImpl::readinessRank)
                    .thenComparingInt(summary -> -summary.unreadyCount())
                    .thenComparing(ProjectServiceImpl::endDateKey, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(summary -> summary.project().getSequenceNo(),
                            Comparator.nullsLast(Comparator.reverseOrder()));

            /*
             * 마감 임박 순 — "곧 닫히는 것".
             *
             * 아직 안 지난 마감만 가까운 순으로 앞에 세우고, 지난 마감은 뒤로 보내되 그 안에서는
             * 최근 순이다. 지난 마감을 그냥 오름차순에 두면 가장 오래된 것이 맨 위에 온다.
             */
            case DUE_SOON -> closedLast
                    .thenComparingInt(summary -> isPast(endDateKey(summary), today) ? 1 : 0)
                    .thenComparing(summary -> dateOrder(endDateKey(summary), today),
                            Comparator.nullsLast(Comparator.naturalOrder()));

            /*
             * 시작 임박 순 — "곧 열리는 것".
             *
             * 18차 R4에서 라벨(`시작 임박 순`)과 동작(`시작 이른 순`)이 어긋난다는 지적을 받았고,
             * 프론트 의견대로 **동작을 라벨에 맞췄다** — 이 화면에서 지난 시작일은 볼 이유가 없다.
             */
            case START_DATE -> closedLast
                    .thenComparingInt(summary -> isPast(startDateKey(summary), today) ? 1 : 0)
                    .thenComparing(summary -> dateOrder(startDateKey(summary), today),
                            Comparator.nullsLast(Comparator.naturalOrder()));
        };
    }

    /** 준비 필요 순의 그룹. 작을수록 앞이다 — PREP → 준비된 PLANNED → RUNNING. */
    private static int readinessRank(ProjectSummary summary) {
        ProjectLifecycleStatus status = summary.project().getLifecycleStatus();
        if (status == ProjectLifecycleStatus.PLANNED) {
            return summary.readiness() == ProjectReadiness.PREP ? 0 : 1;
        }
        return status == ProjectLifecycleStatus.RUNNING ? 2 : 3;
    }

    private static boolean isClosed(ProjectSummary summary) {
        return summary.project().getLifecycleStatus() == ProjectLifecycleStatus.CLOSED;
    }

    private static LocalDate endDateKey(ProjectSummary summary) {
        return summary.project().getEndDate();
    }

    private static LocalDate startDateKey(ProjectSummary summary) {
        return summary.project().getStartDate();
    }

    /** 날짜가 없으면 "지나지 않은 것"으로 본다 — 미정인 회차를 지난 일 뒤로 밀지 않는다. */
    private static boolean isPast(LocalDate date, LocalDate today) {
        return date != null && date.isBefore(today);
    }

    /**
     * 한 그룹 안의 정렬 키.
     *
     * <p>지나지 않은 날짜는 <b>가까운 순</b>(오름차순)이고, 지난 날짜는 <b>최근 순</b>(내림차순)이다.
     * 둘을 한 비교자에 담으려고 지난 쪽은 오늘로부터의 거리로 뒤집는다.
     */
    private static LocalDate dateOrder(LocalDate date, LocalDate today) {
        if (date == null) {
            return null;
        }
        if (!date.isBefore(today)) {
            return date;
        }
        // 오늘을 축으로 대칭시키면 가장 최근 과거가 가장 작은 값이 된다.
        return today.plusDays(today.toEpochDay() - date.toEpochDay());
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
                countConceptCandidates(projectId, orgId),
                List.of(), List.of(),
                // 생성·수정 응답도 목록과 같은 마감을 말해야 한다 — 방금 정한 값을 되읽는 자리다.
                projectDependencyRepository.findRoundSchedules(List.of(projectId)).get(projectId));
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