package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumTeachesMapping;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.MappingStatus;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.projectexecution.domain.ConceptSetStatus;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
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

    @Override
    @Transactional
    public void markRunning(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = projectRepository.findByProjectIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ApiException(ProjectExecutionErrorCode.PROJECT_NOT_FOUND));
        project.start(actorUserId);
    }

    @Override
    @Transactional
    public List<ProjectRequirement> replaceRequirements(
            UUID projectId, UUID orgId, List<String> requirementTitles, UUID actorUserId) {

        if (!projectRepository.existsByProjectIdAndOrgId(projectId, orgId)) {
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
        Project project = projectRepository.findByProjectIdAndOrgId(projectId, orgId)
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
        if (projectRepository.existsByCohortIdAndOrgIdAndNameAndDeletedAtIsNull(cohortId, orgId, name)) {
            throw new ApiException(ProjectExecutionErrorCode.PROJECT_NAME_DUPLICATED,
                    "이미 존재하는 프로젝트명입니다: " + name);
        }
        int nextSequenceNo = projectRepository.findByCohortIdAndOrgIdOrderByCreatedAtDesc(cohortId, orgId).size() + 1;

        Project project = category == ProjectCategory.MINI_PROJECT
                ? Project.createMiniProject(orgId, cohortId, name, nextSequenceNo, startDate, endDate, actorUserId)
                : Project.createBigProject(orgId, cohortId, name, nextSequenceNo, startDate, endDate, actorUserId);

        return projectRepository.save(project);
    }

    @Override
    public List<Project> findProjects(UUID cohortId, UUID orgId) {
        return projectRepository.findByCohortIdAndOrgIdOrderByCreatedAtDesc(cohortId, orgId);
    }

    @Override
    public Project findProject(UUID projectId, UUID orgId) {
        return projectRepository.findByProjectIdAndOrgId(projectId, orgId)
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
        List<Project> projects = projectRepository.findByCohortIdAndOrgIdOrderByCreatedAtDesc(cohortId, orgId);
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
                Project project = projectRepository.findByProjectIdAndOrgId(link.getProjectId(), orgId).orElse(null);
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

        List<ConceptCandidate> candidates = new ArrayList<>();
        for (ProjectCurriculum link : links) {
            List<CurriculumTeachesMapping> mappings = mappingRepository.findActiveCandidatesByVersion(
                    link.getCurriculumVersionId(), orgId, MappingStatus.ACTIVE);
            for (CurriculumTeachesMapping mapping : mappings) {
                candidates.add(new ConceptCandidate(
                        mapping.getMappingId(), mapping.getTeachesId(),
                        mapping.getExtractedName(), mapping.getSourceDescription()));
            }
        }
        return candidates;
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
}