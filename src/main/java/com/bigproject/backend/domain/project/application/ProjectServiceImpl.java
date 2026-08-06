package com.bigproject.backend.domain.project.application;

import com.bigproject.backend.domain.project.domain.ConceptSetStatus;
import com.bigproject.backend.domain.project.domain.Project;
import com.bigproject.backend.domain.project.domain.ProjectCategory;
import com.bigproject.backend.domain.project.domain.ProjectRequirement;
import com.bigproject.backend.domain.project.domain.ProjectVerificationConcept;
import com.bigproject.backend.domain.project.domain.ProjectVerificationConceptSet;
import com.bigproject.backend.domain.project.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.project.infrastructure.ProjectRequirementRepository;
import com.bigproject.backend.domain.project.infrastructure.ProjectVerificationConceptRepository;
import com.bigproject.backend.domain.project.infrastructure.ProjectVerificationConceptSetRepository;
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
    private final ProjectVerificationConceptRepository verificationConceptRepository;
    private final ProjectVerificationConceptSetRepository verificationConceptSetRepository;

    @Override
    @Transactional
    public void markRunning(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = projectRepository.findByProjectIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
        project.start(actorUserId);
    }

    @Override
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

    @Override
    public String resolveMiniProjectRoundLabel(UUID projectId, UUID orgId) {
        Project project = projectRepository.findByProjectIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));

        if (project.getProjectCategory() != ProjectCategory.MINI_PROJECT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "빅프로젝트에는 회차 라벨이 없습니다.");
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
            // 정상 흐름에서는 나올 수 없다 — project는 방금 findByProjectIdAndOrgId로 존재를 확인했고
            // MINI_PROJECT 카테고리도 확인했으니, 같은 조건의 목록에 반드시 포함돼야 한다.
            // 여기 걸리면 목록 조회 조건이 단건 조회 조건과 어긋난 것이니 버그로 취급한다.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "회차 번호를 계산할 수 없습니다.");
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
            // 활성(ACTIVE) 세트에 속한 것만 "현재 쓰인 회차"로 본다 — 교체된 과거 세트는 제외.
            if (set == null || set.getStatus() != ConceptSetStatus.ACTIVE) {
                continue;
            }
            labels.add(resolveMiniProjectRoundLabel(set.getProjectId(), orgId));
        }
        return labels;
    }
}