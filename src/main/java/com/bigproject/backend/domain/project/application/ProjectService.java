package com.bigproject.backend.domain.project.application;

import com.bigproject.backend.domain.project.domain.ExtractionScope;
import com.bigproject.backend.domain.project.domain.Project;
import com.bigproject.backend.domain.project.domain.ProjectType;
import com.bigproject.backend.domain.project.domain.TargetScope;
import com.bigproject.backend.domain.project.infrastructure.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 프로젝트(회차 컨테이너) 생성·조회·종료를 처리하는 서비스
// 조회는 기본 읽기 전용, 변경 메서드에만 따로 @Transactional을 붙임
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;

    @Transactional
    public Project createProject(UUID orgId, UUID cohortId, String name, ProjectType type,
                                 TargetScope targetScope, UUID targetClassId,
                                 LocalDate startDate, LocalDate endDate, UUID actorUserId) {

        // 같은 기수 안에서 프로젝트 이름이 겹치면 안 되는데 DB에 제약이 없어서 여기서 확인
        // (동시 요청 두 건은 이 검사를 통과할 수 있다. 유니크 인덱스가 추가되면 DB가 최종 방어선이 된다)
        if (projectRepository.existsByCohortIdAndOrgIdAndNameAndDeletedAtIsNull(cohortId, orgId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 프로젝트 이름입니다: " + name);
        }

        Project project = Project.builder()
                .orgId(orgId)
                .cohortId(cohortId)
                .name(name)
                .type(type)
                .targetScope(targetScope)
                .targetClassId(targetClassId)
                .startDate(startDate)
                .endDate(endDate)
                .createdBy(actorUserId)
                .build();

        return projectRepository.save(project);
    }

    public Project findProject(UUID projectId, UUID orgId) {
        return projectRepository.findByProjectIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
    }

    public List<Project> findProjects(UUID cohortId, UUID orgId) {
        return projectRepository.findByCohortIdAndOrgIdOrderByCreatedAtDesc(cohortId, orgId);
    }

    // checkpoint가 처음 열릴 때 CheckpointService가 이 메서드를 호출해서 프로젝트 상태를 같이 넘긴다.
    // 프로젝트 혼자 RUNNING으로 못 바뀌게 해서 "checkpoint 없이 진행 중인 프로젝트"가 안 생기게 한다
    @Transactional
    public void markRunning(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = findProject(projectId, orgId);
        project.start(actorUserId);
    }

    @Transactional
    public void closeProject(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = findProject(projectId, orgId);
        project.close(actorUserId);
    }

    @Transactional
    public void deleteProject(UUID projectId, UUID orgId, UUID actorUserId) {
        Project project = findProject(projectId, orgId);
        project.softDelete(actorUserId);
    }
}
