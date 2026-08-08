package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ConceptCandidateResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ConfirmConceptsRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.CreateProjectRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.LinkCurriculumRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.LinkCurriculumResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ProjectResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ReplaceRequirementsRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.UpdateProjectScheduleRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Project Execution", description = "프로젝트 구성·일정·요구사항 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(
            summary = "기수 프로젝트 목록 | ✅ 사용 가능",
            description = """
					기수 안의 프로젝트 전체를 최신순으로 조회한다.
 
					**요청**
					- cohortId (경로): 조회할 기수 ID
 
					**응답 (200)**
					- 프로젝트 목록(필드는 아래 "프로젝트 상세 조회" 응답과 동일)
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로젝트 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
    })
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

    @Operation(
            summary = "프로젝트 생성 | ✅ 사용 가능",
            description = """
					오퍼레이터가 기수 안에 프로젝트(미프/빅프)를 만든다.
 
					**요청**
					- cohortId (경로): 프로젝트를 만들 기수 ID
					- name (필수): 프로젝트명. 같은 기수 안에서 중복되면 409
					- category (필수): MINI_PROJECT / BIG_PROJECT
					- startDate / endDate (필수): 프로젝트 기간
 
					**응답 (201)**
					- projectId: 생성된 프로젝트 ID
					- cohortId: 소속 기수 ID
					- name: 프로젝트명
					- sequenceNo: 기수 내 순번(생성 순서 그대로 부여)
					- category: MINI_PROJECT / BIG_PROJECT
					- status: 생성 직후 항상 PLANNED
					- startDate / endDate: 프로젝트 기간
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "프로젝트 생성 성공"),
            @ApiResponse(responseCode = "400", description = "필수값 누락"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "409", description = "같은 기수에 이미 존재하는 프로젝트명"),
    })
    @PostMapping("/cohorts/{cohortId}/projects")
    public ResponseEntity<ProjectResponse> createProject(
            @Parameter(description = "프로젝트를 만들 기수 ID") @PathVariable UUID cohortId,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        Project project = projectService.createProject(
                orgId, cohortId, request.name(), request.category(), request.startDate(), request.endDate(), actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    @Operation(
            summary = "프로젝트 상세 조회 | ✅ 사용 가능",
            description = """
					프로젝트 하나의 상세 정보를 조회한다.
 
					**요청**
					- projectId (경로): 조회할 프로젝트 ID
 
					**응답 (200)**
					- projectId / cohortId: 프로젝트 ID / 소속 기수 ID
					- name / sequenceNo: 프로젝트명 / 기수 내 순번
					- category: MINI_PROJECT / BIG_PROJECT
					- status: PLANNED(생성됨) / RUNNING(진행 중) / CLOSED(종료)
					- startDate / endDate: 프로젝트 기간
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로젝트 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음"),
    })
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ProjectResponse> findProject(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(ProjectResponse.from(projectService.findProject(projectId, orgId)));
    }

    @Operation(
            summary = "프로젝트 일정 수정 | ✅ 사용 가능",
            description = """
					프로젝트의 시작일·종료일을 변경한다.
 
					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- startDate / endDate (필수): 새 프로젝트 기간
 
					**응답 (200)**
					- 변경된 프로젝트 정보(응답 필드는 "프로젝트 상세 조회"와 동일)
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로젝트 일정 수정 성공"),
            @ApiResponse(responseCode = "400", description = "필수값 누락 또는 종료일이 시작일보다 빠름"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음"),
    })
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

    @Operation(
            summary = "프로젝트 요구사항 전체 교체",
            description = """
					요구사항 문구 목록을 전체 교체한다. 보낸 목록이 그대로 최종 상태가 된다 —
					기존에 있었는데 이번 목록에 없는 문구는 자동 폐기(retire)되고, 새 문구는 추가된다.
 
					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- requirementTitles (필수): 요구사항 문구 목록. 완전히 같은 문구만 "유지"로 인식하고,
					  조금이라도 다르면 기존 것 폐기 + 새 문구 추가로 처리한다
 
					**응답 (200)**
					- 본문 없음
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요구사항 교체 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음"),
    })
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

    @Operation(
            summary = "프로젝트 교안 연결 | ✅ 사용 가능",
            description = """
					미니프로젝트에 교안 버전을 연결한다. project_curriculum DDL 제약을 그대로 따른다 —
					대상 버전은 성공한 분석과 승인된(ACTIVE) 개념 매핑을 최소 1건 가지고 있어야 한다.
 
					**요청**
					- projectId (경로): 대상 프로젝트 ID(MINI_PROJECT만 가능, BIG_PROJECT는 400)
					- curriculumVersionId (필수): 연결할 교안 버전 ID
 
					**응답 (201)**
					- projectCurriculumId: 연결 ID
					- projectId / curriculumVersionId: 연결 대상
					- sequenceNo: 프로젝트 내 표시 순서(기존 최대값+1로 자동 채번)
					- linkedAt: 연결 시각
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "교안 연결 성공"),
            @ApiResponse(responseCode = "400", description = "빅프로젝트이거나, 성공한 분석/승인된 매핑이 없는 버전"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "프로젝트 또는 교안 버전을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이미 연결된 교안 버전"),
    })
    @PostMapping("/projects/{projectId}/curricula")
    public ResponseEntity<LinkCurriculumResponse> linkCurriculum(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody LinkCurriculumRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        ProjectCurriculum link = projectService.linkCurriculum(projectId, orgId, request.curriculumVersionId(), actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(LinkCurriculumResponse.from(link));
    }

    @Operation(
            summary = "검증개념 후보 조회 | ✅ 사용 가능",
            description = """
					프로젝트에 연결된 교안들의 승인된 매핑 전체를 검증개념 후보로 내려준다.
 
					**요청**
					- projectId (경로): 대상 프로젝트 ID
 
					**응답 (200)**
					- mappingId: 매핑 ID(검증개념 확정 시 이 ID를 보낸다)
					- teachesId: 공용 개념 원장 ID
					- extractedName / description: 항목 이름 / 정의문
 
					**참고** — 프로젝트에 연결된 교안(project_curriculum)이 없으면 항상 빈 배열이다.
					`POST /projects/{projectId}/curricula`로 먼저 교안을 연결해야 한다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증개념 후보 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음"),
    })
    @GetMapping("/projects/{projectId}/concept-candidates")
    public ResponseEntity<List<ConceptCandidateResponse>> findConceptCandidates(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<ConceptCandidateResponse> response = projectService.findConceptCandidates(projectId, orgId).stream()
                .map(ConceptCandidateResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "검증개념 확정",
            description = """
					선택한 매핑들을 이 프로젝트의 검증개념으로 확정한다. 기존 활성 세트는 자동으로 교체(supersede)되며,
					과거 세트는 지워지지 않고 이력으로 남는다.
 
					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- mappingIds (필수): 확정할 매핑 ID 목록(검증개념 후보 조회 응답의 mappingId)
 
					**응답 (200)**
					- 본문 없음
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증개념 확정 성공"),
            @ApiResponse(responseCode = "400", description = "존재하지 않는 매핑 ID가 포함됨"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "프로젝트를 찾을 수 없음"),
    })
    @PutMapping("/projects/{projectId}/concepts")
    public ResponseEntity<Void> confirmConcepts(
            @Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
            @Valid @RequestBody ConfirmConceptsRequest request
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        projectService.confirmConcepts(projectId, orgId, request.mappingIds(), actorUserId);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "프로젝트 회차 목록 | ✅ 사용 가능",
            description = """
					⚠ 임시: 전용 회차(round) 엔티티가 아직 없어, 같은 기수의 미니프로젝트 목록을 회차로 취급한다.
					각 항목이 곧 하나의 회차이며, roundId는 projectId와 같다.
 
					**요청**
					- projectId (경로): 기준 프로젝트 ID(같은 기수의 회차 전체를 조회하는 기준점)
 
					**응답 (200)**
					- 미니프로젝트 목록(필드는 "프로젝트 상세 조회"와 동일)
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "기준 프로젝트를 찾을 수 없음"),
    })
    @GetMapping("/projects/{projectId}/rounds")
    public ResponseEntity<List<ProjectResponse>> findRounds(
            @Parameter(description = "기준 프로젝트 ID(같은 기수의 회차 전체를 조회)") @PathVariable UUID projectId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        Project baseProject = projectService.findProject(projectId, orgId);
        List<ProjectResponse> response = projectService.findProjects(baseProject.getCohortId(), orgId).stream()
                .filter(p -> p.getProjectCategory() == ProjectCategory.MINI_PROJECT)
                .map(ProjectResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "프로젝트 회차 일정 수정 | ✅ 사용 가능",
            description = """
					⚠ 임시: roundId는 projectId와 동일하게 취급한다. 실제로는
					`PATCH /projects/{projectId}`(일정 수정)와 완전히 동일한 동작이다.
 
					**요청**
					- projectId (경로): 기준 프로젝트 ID(현재 미사용, URL 구조상만 존재)
					- roundId (경로): 일정을 바꿀 회차 ID(=projectId와 동일하게 취급)
					- startDate / endDate (필수): 새 일정
 
					**응답 (200)**
					- 변경된 프로젝트 정보(응답 필드는 "프로젝트 상세 조회"와 동일)
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회차 일정 수정 성공"),
            @ApiResponse(responseCode = "400", description = "필수값 누락 또는 종료일이 시작일보다 빠름"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "회차(프로젝트)를 찾을 수 없음"),
    })
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