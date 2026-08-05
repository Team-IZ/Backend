package com.bigproject.backend.domain.project.application;

import com.bigproject.backend.domain.project.domain.Project;
import com.bigproject.backend.domain.project.domain.ProjectRequirement;
import com.bigproject.backend.domain.project.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.project.infrastructure.ProjectRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectRequirementRepository requirementRepository;

    @Override
    @Transactional
    public void markRunning(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = projectRepository.findByProjectIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
        project.start(actorUserId);
    }
    @Transactional
    public List<ProjectRequirement> replaceRequirements(
            UUID projectId, UUID orgId, List<String> requirementTitles, UUID actorUserId) {

        if (!projectRepository.existsByProjectIdAndOrgId(projectId, orgId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.");
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
}