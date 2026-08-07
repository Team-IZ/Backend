package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.presentation.dto.CreateProjectRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ProjectResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ReplaceRequirementsRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.UpdateProjectScheduleRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Project Execution", description = "프로젝트 구성·일정·요구사항 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(summary = "기수 프로젝트 목록", description = "**상태**: ✅ 사용 가능\n\n기수 안의 프로젝트 전체를 최신순으로 조회한다.")
    @GetMapping("/cohorts/{cohortId}/projects")
    public ResponseEntity<List<ProjectResponse>> findProjects(
            @Parameter(description = "기수 ID") @PathVariable UUID cohortId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<ProjectResponse> response = projectService.findProjects(cohortId, orgId).stream()
                .map(ProjectResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "프로젝트 생성", description = "**상태**: ✅ 사용 가능\n\n오퍼레이터가 기수 안에 프로젝트(미프/빅프)를 만든다.")
    @PostMapping("/cohorts/{cohortId}/projects")
    public ResponseEntity<ProjectResponse> createProject(
            @Parameter(description = "기수 ID") @PathVariable UUID cohortId,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        Project project = projectService.createProject(
                orgId, cohortId, request.name(), request.category(), request.startDate(), request.endDate(), actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    @Operation(summary = "프로젝트 상세 조회", description = "**상태**: ✅ 사용 가능\n\n프로젝트 하나의 상세 정보를 조회한다.")
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ProjectResponse> findProject(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(ProjectResponse.from(projectService.findProject(projectId, orgId)));
    }

    @Operation(summary = "프로젝트 일정 수정", description = "**상태**: ✅ 사용 가능\n\n프로젝트의 시작일·종료일을 변경한다. 종료된 프로젝트는 수정할 수 없다.")
    @PatchMapping("/projects/{projectId}")
    public ResponseEntity<ProjectResponse> updateSchedule(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectScheduleRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        Project project = projectService.updateSchedule(projectId, orgId, request.startDate(), request.endDate(), actorUserId);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }

    @Operation(summary = "프로젝트 요구사항 전체 교체", description = "**상태**: ✅ 사용 가능\n\n요구사항 문구 목록을 전체 교체한다. 빠진 문구는 자동 폐기(retire), 새 문구는 추가된다.")
    @PutMapping("/projects/{projectId}/requirements")
    public ResponseEntity<Void> replaceRequirements(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody ReplaceRequirementsRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        projectService.replaceRequirements(projectId, orgId, request.requirementTitles(), actorUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "검증개념 후보 조회", description = "**상태**: ✅ 사용 가능\n\n프로젝트에 연결된 교안들의 승인된 매핑 전체를 검증개념 후보로 내려준다.")
    @GetMapping("/projects/{projectId}/concept-candidates")
    public ResponseEntity<List<com.bigproject.backend.domain.projectexecution.presentation.dto.ConceptCandidateResponse>> findConceptCandidates(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<com.bigproject.backend.domain.projectexecution.presentation.dto.ConceptCandidateResponse> response =
                projectService.findConceptCandidates(projectId, orgId).stream()
                        .map(com.bigproject.backend.domain.projectexecution.presentation.dto.ConceptCandidateResponse::from)
                        .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "검증개념 확정", description = "**상태**: ✅ 사용 가능\n\n선택한 매핑들을 이 프로젝트의 검증개념으로 확정한다. 기존 활성 세트는 자동으로 교체된다.")
    @PutMapping("/projects/{projectId}/concepts")
    public ResponseEntity<Void> confirmConcepts(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody com.bigproject.backend.domain.projectexecution.presentation.dto.ConfirmConceptsRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        projectService.confirmConcepts(projectId, orgId, request.mappingIds(), actorUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "프로젝트 회차 목록",
            description = "**상태**: ✅ 사용 가능\n\n⚠ 임시: 전용 회차(round) 엔티티가 아직 없어, 같은 기수의 미니프로젝트 목록을 회차로 취급한다. " +
                    "각 항목이 곧 하나의 회차이며, roundId는 projectId와 같다."
    )
    @GetMapping("/projects/{projectId}/rounds")
    public ResponseEntity<List<ProjectResponse>> findRounds(
            @Parameter(description = "기준 프로젝트 ID(같은 기수의 회차 전체를 조회)") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        Project baseProject = projectService.findProject(projectId, orgId);
        List<ProjectResponse> response = projectService.findProjects(baseProject.getCohortId(), orgId).stream()
                .filter(p -> p.getProjectCategory() == com.bigproject.backend.domain.projectexecution.domain.ProjectCategory.MINI_PROJECT)
                .map(ProjectResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "프로젝트 회차 일정 수정",
            description = "**상태**: ✅ 사용 가능\n\n⚠ 임시: roundId는 projectId와 동일하게 취급한다. 실제로는 " +
                    "`PATCH /projects/{projectId}`(일정 수정)와 동일한 동작이다."
    )
    @PatchMapping("/projects/{projectId}/rounds/{roundId}")
    public ResponseEntity<ProjectResponse> updateRoundSchedule(
            @Parameter(description = "기준 프로젝트 ID") @PathVariable UUID projectId,
            @Parameter(description = "회차 ID(=projectId와 동일하게 취급)") @PathVariable UUID roundId,
            @Valid @RequestBody UpdateProjectScheduleRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        Project project = projectService.updateSchedule(roundId, orgId, request.startDate(), request.endDate(), actorUserId);
        return ResponseEntity.ok(ProjectResponse.from(project));
    }
}