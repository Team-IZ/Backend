package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectRequirement;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProjectService {

    void markRunning(UUID projectId, UUID orgId, UUID actorUserId);

    List<ProjectRequirement> replaceRequirements(
            UUID projectId, UUID orgId, List<String> requirementTitles, UUID actorUserId);

    String resolveMiniProjectRoundLabel(UUID projectId, UUID orgId);

    List<String> findRoundLabelsUsingTeaches(UUID teachesId, UUID orgId);

    List<String> findRoundLabelsUsingCurriculum(UUID curriculumVersionId, UUID orgId);

    Project createProject(UUID orgId, UUID cohortId, String name, ProjectCategory category,
                          LocalDate startDate, LocalDate endDate, UUID actorUserId);

    List<Project> findProjects(UUID cohortId, UUID orgId);

    Project findProject(UUID projectId, UUID orgId);

    Project updateSchedule(UUID projectId, UUID orgId, LocalDate startDate, LocalDate endDate, UUID actorUserId);

    List<ConceptCandidate> findConceptCandidates(UUID projectId, UUID orgId);

    void confirmConcepts(UUID projectId, UUID orgId, List<UUID> mappingIds, UUID actorUserId);

    record ConceptCandidate(UUID mappingId, UUID teachesId, String extractedName, String description) {
    }

    /** 이 기수(cohortId) 소속 프로젝트들이 연결한 curriculum_version_id 전체(중복 제거). */
    List<UUID> findLinkedCurriculumVersionIds(UUID cohortId, UUID orgId);

    /** 주어진 curriculum_version_id들 중 하나라도 연결한 다른 기수들의 cohortId(자기 자신 제외, 중복 제거). */
    List<UUID> findCohortsSharingAnyCurriculum(List<UUID> curriculumVersionIds, UUID excludeCohortId, UUID orgId);

    ProjectCurriculum linkCurriculum(UUID projectId, UUID orgId, UUID curriculumVersionId, UUID actorUserId);

}