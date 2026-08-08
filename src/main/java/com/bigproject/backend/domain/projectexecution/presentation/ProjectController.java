package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.application.ClassProgressService;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
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
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Project Execution", description = "프로젝트 구성·일정·요구사항 API")
@Tag(name = "Project", description = "프로젝트 진행 현황 조회")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
// 경로는 메서드마다 다르지만 응답 형식은 전부 JSON이다. 클래스 단위로 한 번 걸면
// 새 엔드포인트가 생겨도 따라온다 — 안 걸면 스펙에 content-type이 `*/*`로 나가고,
// 생성기가 응답 타입을 좁히지 못해 프론트가 any를 받는다(OpenApiDocumentTest가 잡는다).
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ProjectController {

	private final ProjectService projectService;
	private final CurrentUserResolver currentUserResolver;
	private final ClassProgressService classProgressService;

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
			summary = "프로젝트 요구사항 전체 교체 | ✅ 사용 가능",
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
			summary = "검증개념 확정 | ✅ 사용 가능",
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

	@Operation(
			operationId = "findProjectClassProgress",
			summary = "반별 제출·분석·응시 현황 조회 | ✅ 사용 가능",
			description = """
					프로젝트 회차의 반별 진행을 제출 → 분석 → 응시 순으로 한 번에 조회합니다.
					대시보드 '이번 회차' 카드와 프로젝트 상세 '현황' 화면이 함께 쓰는 API입니다.

					단계별 깔때기라 각 단계의 분모가 앞 단계의 분자입니다.
					제출률은 submittedCount / targetTraineeCount,
					응시율은 assessedCount / analysisSucceededCount 입니다.
					응시율의 분모가 제출 단계에서 나오므로 두 지표를 나눠 호출하지 않습니다.

					제출은 팀 단위 원장이지만 이 화면은 인원 기준으로 환산합니다.

					분석 상태는 성공·실패·부분 성공·진행 중 네 갈래를 모두 내려줍니다.
					부분 성공(PARTIAL)은 분석 완료로 세지 않으므로 응시율 분모에서 빠집니다.
					네 값을 더하면 제출 인원과 같아 어느 열에도 잡히지 않고 사라지는 인원이 없습니다.

					회차는 projectId와 roundNo로 특정합니다. round_no는 프로젝트 안에서만 유일합니다.

					## 요청

					| 파라미터 | 위치 | 필수 | 타입 | 설명 |
					|---|---|---|---|---|
					| `projectId` | 경로 | **필수** | UUID | 조회할 프로젝트 ID |
					| `roundNo` | 쿼리 | 선택(기본 `1`) | int | 조회할 회차 번호. 프로젝트 안에서만 유일하며 1 미만이면 400 |

					## 응답 — 최상위

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `projectId` | UUID | 조회한 프로젝트 |
					| `projectName` | string | 프로젝트 이름 |
					| `assessmentRoundId` | UUID | 이 회차의 내부 식별자 |
					| `roundNo` | int | 회차 번호(프로젝트 안에서만 유일) |
					| `roundName` | string | 회차 이름 |
					| `totalRoundCount` | int | 이 프로젝트에 속한 전체 회차 수. 화면의 'N차 / M회' 표기에 씁니다 |
					| `submissionDueAt` | timestamp | 제출 마감 시각 |
					| `reportPublishMode` | enum | 리포트 발행 방식. 현재는 마감 후 전 반을 한 번에 발행하는 `ROUND_BATCH`만 존재 |
					| `reportPublished` | boolean | 이 회차 리포트가 발행됐는지 여부. `false`면 화면에 '미발행'을 표시 |
					| `summary` | object | 회차 전체 진행 현황 합계. classes[]를 합산한 값이 아니라 회차 단위로 직접 집계 |
					| `classes[]` | array | 반 행 목록. 반 이름 오름차순 |
					| `conceptMatches[]` | array | 검증 개념별 코드 매칭 현황 |

					**`summary`** — 회차 전체 합계

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `targetTraineeCount` | long | 이번 회차 수행 대상 교육생 수(기수 총원). 제출률의 분모 |
					| `submittedCount` | long | 제출을 마친 교육생 수 |
					| `analysisTargetCount` | long | 분석 대상 교육생 수. `submittedCount`와 값이 같습니다 — 단계별 분모를 필드 이름으로도 드러내려고 따로 둡니다 |
					| `analysisSucceededCount` | long | 분석이 성공한 교육생 수 |
					| `assessmentTargetCount` | long | 응시 대상 교육생 수. `analysisSucceededCount`와 값이 같습니다 |
					| `assessedCount` | long | 응시(INITIAL 완료)를 마친 교육생 수 |

					**`classes[]`** — 반 한 행

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `classId` | UUID | 반 식별자 |
					| `className` | string | 반 이름 |
					| `targetTraineeCount` | long | 회차 수행 대상 교육생 수. 제출률의 분모 |
					| `submittedCount` | long | 소속 팀이 제출을 마친 교육생 수 |
					| `analysisSucceededCount` | long | 분석이 성공한 교육생 수. 응시율의 분모이며 PARTIAL은 포함하지 않음 |
					| `analysisFailedCount` | long | 분석이 실패한 교육생 수(**인원** 기준) |
					| `analysisPartialCount` | long | 분석이 부분 성공(PARTIAL)한 교육생 수. 응시율 분모에서 빠짐 |
					| `analysisInProgressCount` | long | 분석이 대기·진행 중인 교육생 수 |
					| `assessedCount` | long | 최초 응시(INITIAL)를 완료한 교육생 수 |
					| `notAttendedCount` | long | 미응시(NOT_ATTENDED) 교육생 수 |
					| `sessionIncompleteCount` | long | 중단(SESSION_INCOMPLETE) 교육생 수 |
					| `invalidAttemptCount` | long | 무효 확정(CONFIRMED_INVALID) 교육생 수. 무효 확인 중(PENDING)은 미포함 |
					| `managerNames[]` | string[] | 활성 담당 매니저 이름 목록. 비어 있으면 화면의 '담당 없음'이며 대시보드 미배정 경보와 같은 조건 |
					| `failedTeams[]` | array | 분석이 실패한 팀 목록(**팀** 기준). 크기가 `analysisFailedCount`(인원 기준)와 다를 수 있습니다 — 한 팀에 팀원이 여럿이면 인원 수가 더 큽니다 |

					submittedCount = analysisSucceededCount + analysisFailedCount + analysisPartialCount + analysisInProgressCount

					**`classes[].failedTeams[]`** — 분석 실패 팀 한 건. 팀·회차별 최신 제출과 그 제출에 매인 최신 분석 시도만 봅니다(재시도 반영)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `teamId` | UUID | 팀 식별자 |
					| `teamName` | string | 팀 이름 |
					| `representativeUserId` | UUID | 대표자(그 팀·회차의 최신 제출을 실행한 사용자) ID |
					| `representativeName` | string | 대표자 이름 |
					| `failureReason` | string? | `analysis_job.failure_reason` 원문. null일 수 있음 |

					**`conceptMatches[]`** — 검증 개념별 코드 매칭 한 행. 문제는 팀 공용이라 팀 단위 매칭 판정을 팀원 인원으로 펼쳐 셉니다

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `teachesId` | UUID | 검증 개념 식별자 |
					| `conceptName` | string | 검증 개념 이름 |
					| `analysedTraineeCount` | long | 분석에 성공한 교육생 수. 매칭률의 분모 |
					| `matchedTraineeCount` | long | 그 개념의 문제를 받은 교육생 수 |
					| `unmatchedTeamCount` | long | 그 개념이 코드에서 발견되지 않아 전원이 문제를 받지 못한 팀 수 |
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반별 현황 조회 성공"),
			@ApiResponse(responseCode = "400", description = "ROUND_NO_INVALID 회차 번호가 1 미만"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · PROJECT_CROSS_ORGANIZATION 다른 기관의 프로젝트"),
			@ApiResponse(responseCode = "404", description = "PROJECT_ROUND_NOT_FOUND 그 프로젝트에 그 번호의 회차가 없음")
	})
	@GetMapping(value = "/projects/{projectId}/class-progress", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ClassProgressResponse> findClassProgress(
			@Parameter(description = "조회할 프로젝트 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID projectId,
			@Parameter(description = "조회할 회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
			@RequestParam(defaultValue = "1") @Min(1) int roundNo,
			@Parameter(hidden = true)
			Authentication authentication
	) {
		return ResponseEntity.ok(
				classProgressService.findClassProgress(projectId, roundNo, authentication.getName()));
	}
}