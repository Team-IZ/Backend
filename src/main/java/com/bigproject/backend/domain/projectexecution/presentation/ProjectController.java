package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.application.ClassProgressService;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectListSort;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ClassProgressResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ConceptCandidateResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ConfirmConceptsRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.CreateProjectRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.LinkCurriculumRequest;
import com.bigproject.backend.domain.projectexecution.presentation.dto.LinkCurriculumResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ProjectDetailResponse;
import com.bigproject.backend.domain.projectexecution.presentation.dto.ProjectListResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
			operationId = "findProjects",
			summary = "기수 프로젝트 목록 | ✅ 사용 가능",
			description = """
					기수 안의 프로젝트를 조회한다. **검색·필터·정렬을 서버가 처리하므로 화면은 파라미터만
					넘기면 된다**(9차 R3).

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `cohortId` | **필수**(경로) | UUID | 조회할 기수 ID |
					| `search` | 선택 | string | 회차 이름 부분검색(대소문자 무시). 비우면 전체 |
					| `curriculumId` | 선택 | UUID | 교안으로 좁힌다. **교안 버전 ID와 자료(material) ID를 모두 받는다** |
					| `status` | 선택 | enum | `PLANNED` · `RUNNING` · `CLOSED`. 비우면 전체 |
					| `sort` | 선택 | enum | `READINESS`(준비 필요 순, **기본**) · `DUE_SOON`(마감 임박 순) · `START_DATE`(시작 이른 순) |

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `projects[]` | array | 필터·정렬이 적용된 목록 |
					| `total` | int | **필터 적용 후** 개수. `projects`의 길이와 같다 |
					| `counts` | object | 상태별 개수. **필터와 무관한 기수 전체 모집단** |

					⚠️ **응답이 배열에서 객체로 바뀌었다.** `counts`를 실을 자리가 필요했기 때문이다 —
					상태별 개수는 필터와 무관한 모집단 기준이라 걸러진 배열에서는 셀 수 없다.
					기수·반·매니저 목록이 이미 같은 모양이다.

					**`counts`는 `PLANNED`·`RUNNING`·`CLOSED` 세 키가 항상 모두 있고**, 0건인 상태는 0으로 온다.
					세 값을 더하면 이 기수의 전체 회차 수다.

					### `projects[]` 각 항목

					- projectId / cohortId / name / sequenceNo / category / status / startDate / endDate
					- **readiness**: `PREP`(준비 중) · `READY`(준비됨) — 서버가 판정한다(아래)
					- curriculumCount: 연결된 교안 수. 0이면 화면의 `교안 연결 안 됨`
					- conceptCount: 확정된 검증 개념 수. 화면의 `검증 개념 2 / 3건`에서 분자
					- conceptCandidateCount: 검증 개념 후보 수. 화면의 `후보 12건에서 3건`에서 앞 숫자

					## `readiness` — 9차 Q1의 답(ⓐ 서버 판정)

					**`status`에 값을 더하지 않고 축을 나눴다.** 화면이 쓰는 4값
					(`준비 중`·`준비됨`·`진행 중`·`종료`)은 성격이 다른 둘을 겹쳐 놓은 것이다 —
					`RUNNING`·`CLOSED`는 **시간**이 정하고 `PREP`·`READY`는 **구성이 찼는지**가 정한다.
					한 필드에 합치면 *"진행 중인데 교안이 비어 있다"* 같은 실제 상태를 표현할 수 없다.

					화면의 4값은 두 필드를 겹쳐 만든다:

					```ts
					const label = status === 'PLANNED' ? readiness : status;
					// PREP | READY | RUNNING | CLOSED
					```

					**판정 규칙** — 교안·확정 개념·마감일 셋 중 **하나라도 비어 있으면 `PREP`**, 셋 다 차면 `READY`.
					`sort=READINESS`가 쓰는 규칙과 **같은 자리**에서 계산하므로 목록의 순서와 배지가 어긋날 수 없다.

					`RUNNING`·`CLOSED` 회차에도 계산되어 온다 — 개강 후 교안이 비는 것은 실제로 일어나는
					상태라 감추지 않는다. 화면이 안 쓰면 무시하면 된다.

					**세 숫자는 목록 화면이 셀마다 그리는 값이다**(9차 R1). 화면이 직접 세려면 회차마다
					교안·개념·후보를 따로 물어야 해서, 회차가 6~8건인 목록 하나에 조회가 그만큼 늘어난다.

					교안 이름·개념 이름처럼 **목록이 아니라 상세에서 쓰는 값**은 여기 없다 —
					`GET /projects/{projectId}`가 배열로 내려준다.

					## `READINESS`(준비 필요 순)의 판정 규칙

					**덜 준비된 회차가 앞**이다. 이 목록이 답하는 질문이 "뭐부터 손대야 하나"라서 기본값이다.

					1. **미충족 항목 수**가 많은 순 — 교안 0건 · 확정 개념 0건 · 마감일 없음 셋 중 몇 개인지
					2. 같으면 **마감이 이른 순**(마감 없는 회차가 뒤)
					3. 그것도 같으면 **최근 회차 순**

					💡 정렬을 서버가 하기로 한 이상 규칙도 서버에 두었다 — 화면이 정렬하고 서버가 순서를 매기면
					같은 규칙이 두 곳에 생긴다. **이 규칙이 곧 `readiness` 판정**이라(9차 Q1 ⓐ),
					`unreadyCount() === 0`이면 `READY`다. 순서와 배지가 한 계산에서 나온다.

					## 페이지네이션은 없다

					기수당 회차가 6~8건이라 전량이 한 페이지에 들어간다. 회차가 쌓이면 `page`·`size`를
					추가하겠다 — 그때도 위 파라미터와 `counts`는 그대로 쓸 수 있다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "프로젝트 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED status·sort에 없는 값을 지정함"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수·삭제된 기수 포함) — 22차 R7"),
	})
	@GetMapping("/cohorts/{cohortId}/projects")
	public ResponseEntity<ProjectListResponse> findProjects(
			@Parameter(description = "기수 ID") @PathVariable UUID cohortId,
			@Parameter(description = "회차 이름 부분검색(대소문자 무시)", example = "미니")
			@RequestParam(required = false) String search,
			@Parameter(description = "교안으로 좁힌다. 교안 버전 ID와 자료(material) ID를 모두 받는다")
			@RequestParam(required = false) UUID curriculumId,
			@Parameter(description = "상태로 좁힌다. 생략하면 전체")
			@RequestParam(required = false) ProjectLifecycleStatus status,
			@Parameter(description = "정렬 기준", example = "READINESS")
			@RequestParam(required = false, defaultValue = "READINESS") ProjectListSort sort
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		ProjectService.ProjectList list = projectService.findProjectList(
				cohortId, orgId, new ProjectService.ProjectListCriteria(search, curriculumId, status, sort));
		return ResponseEntity.ok(ProjectListResponse.from(list));
	}

	@Operation(
			operationId = "findCurrentProject",
			summary = "기수의 이번 회차 조회 | ✅ 사용 가능",
			description = """
					그 기수에서 **지금 굴러가는 회차 하나**를 돌려준다. OP-01 대시보드의 `이번 회차`
					블록이 이 응답 하나로 그려진다.

					## 🔴 회차 선택 규칙을 서버가 갖는다

					「지금 어느 회차인가」는 화면 취향이 아니라 **도메인 사실**이다. 종전에는 화면이
					`GET /cohorts/{id}/projects`로 전량을 받아 스스로 골랐는데, 그 규칙은 합의된 적이
					없어 서버 정렬이 바뀌면 조용히 다른 회차가 뜨고 같은 판단이 필요한 화면이 늘면
					규칙이 두 곳으로 갈렸다.

					| 순서 | 고르는 것 | 왜 |
					|---|---|---|
					| ① | `RUNNING` 중 **가장 늦게 시작한** 것 | 회차가 겹쳐 열렸으면 나중에 연 쪽이 지금이다 |
					| ② | 없으면 **가장 이른 `PLANNED`** | 다음에 열릴 회차가 지금의 관심사다 |
					| ③ | 그것도 없으면 **마지막 회차** | 전부 끝난 기수도 마지막 결과를 그려야 한다 |

					⚠️ **③이 `CLOSED`를 돌려준다.** 이 응답이 왔다고 "진행 중"이라고 단정하면 안 된다 —
					`status`를 보고 그린다. 응답에 `status`가 함께 나가는 이유다.

					정렬 축은 `sequenceNo`가 먼저이고 `startDate`가 보조다. 정의서가 `sequence_no`를
					"기수 내 전체 프로젝트 운영 순서"로 정의하므로 그것이 권위 축이고, 날짜는 비어
					있을 수 있다.

					## 응답

					`GET /projects/{projectId}`의 요약과 **같은 모양**이다 —
					`projectId` · `name` · `sequenceNo` · `status` · `startDate` · `endDate` +
					`curriculumCount` · `conceptCount` · `conceptCandidateCount`.

					## 🆕 22차 R10 ⓐ — `totalRounds`

					**이 기수의 전체 회차 수**이며 화면의 `3차 / 6회`에서 분모다. `sequenceNo`(분자)는
					있는데 이 값이 없어서, 15차 R1로 만든 이 API를 대시보드가 한 번도 쓰지 못하고
					목록(`GET /cohorts/{id}/projects`)을 계속 부르고 있었다.

					세는 데 조회가 늘지 않는다 — 「이번 회차」를 고르려고 어차피 읽던 목록의 길이다.

					> ⓑ(진행 수치를 함께 싣기)는 아직 반영하지 않았다. 계약이 커지는 일이라
					> 프론트도 ⓐ만이어도 좋다고 했고, 지금은 `class-progress`를 한 번 더 부르면 된다.

					## 🔴 회차가 없으면 `204 No Content`다

					**`404`가 아니다.** 회차를 아직 만들지 않은 기수는 실패가 아니라 정상 상태이고,
					404로 답하면 "그런 기수가 없다"와 구분되지 않는다. 본문이 없으므로 화면은
					`이번 회차 없음`을 그리면 된다.

					## 왜 목록 대신 이것을 쓰나

					목록은 회차 전량과 상태별 집계를 함께 만든다. 대시보드는 그중 **하나만** 쓰고
					나머지를 버렸다. 이 API는 고른 회차 하나만 요약하므로 그 낭비가 없다.

					> 15차 R1로 목록(`GET /cohorts/{id}/projects`) 자체의 N+1도 함께 고쳤다.
					> 목록이 여전히 필요한 화면(OP-03)은 그쪽을 계속 쓰면 된다.

					## 오류

					| 상태 | 언제 |
					|---|---|
					| 204 | 그 기수에 회차가 하나도 없다 (**정상**) |
					| 401 | 액세스 토큰이 없거나 유효하지 않다 |
					""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "이번 회차 조회 성공"),
			@ApiResponse(responseCode = "204", description = "그 기수에 회차가 하나도 없음. 정상 상태이며 본문이 없다"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
	})
	// `/{projectId}`가 아니라 `/cohorts/{cohortId}/projects/current`라 경로 충돌이 없다 —
	// 이 뿌리에는 UUID 경로 변수가 뒤에 오지 않는다.
	@GetMapping("/cohorts/{cohortId}/projects/current")
	public ResponseEntity<ProjectResponse> findCurrentProject(
			@Parameter(description = "기수 ID") @PathVariable UUID cohortId
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		return projectService.findCurrentProject(cohortId, orgId)
				.map(summary -> ResponseEntity.ok(ProjectResponse.from(summary)))
				.orElseGet(() -> ResponseEntity.noContent().build());
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
					- submissionDueAt (선택): 제출 마감 **시각**. 생략하면 `endDate`의 23:59 KST로 파생한다

					**응답 (201)**
					- projectId: 생성된 프로젝트 ID
					- cohortId: 소속 기수 ID
					- name: 프로젝트명
					- sequenceNo: 기수 내 순번(생성 순서 그대로 부여)
					- category: MINI_PROJECT / BIG_PROJECT
					- status: 생성 직후 항상 PLANNED
					- startDate / endDate: 프로젝트 기간
					- submissionDueAt: 방금 정해진 제출 마감 시각(보낸 값 또는 파생값)
					- curriculumCount / conceptCount / conceptCandidateCount: 갓 만든 프로젝트라 전부 0

					## 🔴 22차 R5·R6 — 평가 회차를 **함께 만든다**

					여태 이 API는 `project` 행만 만들고 `project_assessment_round`는 만들지 않았다.
					회차가 있는 프로젝트는 전부 시드로 들어간 것이었고, **화면에서 만든 프로젝트에는
					회차가 없었다.** 그래서 두 가지가 동시에 깨져 있었다.

					- 현황 탭(`class-progress`)이 회차를 못 찾아 답하지 못했다(22차 R6)
					- 제출 마감이 회차에 있는 컬럼이라 **저장할 자리가 없었다**(22차 R5 ①)

					이제 프로젝트와 회차를 **한 트랜잭션**에서 만든다. 회차는 `round_no=1` ·
					`status=PLANNED` · `trigger_type=MANUAL`로 열리며, 응시 창과 리포트 발행 하한은
					비워 둔다 — 셋 다 코드 분석·응시가 끝나야 정해지는 값이라 이 시점에 넣을 사실이 없다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "프로젝트 생성 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락·형식 오류(fieldErrors 동봉)"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "409", description = "PROJECT_NAME_DUPLICATED 같은 기수에 이미 존재하는 프로젝트명 — 이름 입력란에 인라인 오류"),
	})
	@PostMapping("/cohorts/{cohortId}/projects")
	public ResponseEntity<ProjectResponse> createProject(
			@Parameter(description = "프로젝트를 만들 기수 ID") @PathVariable UUID cohortId,
			@Valid @RequestBody CreateProjectRequest request
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		Project project = projectService.createProject(
				orgId, cohortId, request.name(), request.category(), request.startDate(), request.endDate(),
				request.submissionDueAt(), actorUserId);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ProjectResponse.from(projectService.summarize(project, orgId)));
	}

	@Operation(
			operationId = "findProject",
			summary = "프로젝트 상세 조회 | ✅ 사용 가능",
			description = """
					프로젝트 하나의 상세 정보를 조회한다. **프로젝트에 저장한 것(교안·검증개념·요구사항)을
					되읽는 경로가 여기 하나로 모여 있다**(9차 R1) — 상세 화면이 한 번에 필요한 값이라
					셋으로 나누면 화면 진입에 조회가 4건이 된다.

					**요청**
					- projectId (경로): 조회할 프로젝트 ID

					**응답 (200) — 기본**
					- projectId / cohortId: 프로젝트 ID / 소속 기수 ID
					- name / sequenceNo: 프로젝트명 / 기수 내 순번
					- category: MINI_PROJECT / BIG_PROJECT
					- status: PLANNED(생성됨) / RUNNING(진행 중) / CLOSED(종료) — **시간 축**
					- readiness: PREP(준비 중) / READY(준비됨) — **구성 축**. 서버가 판정한다(9차 Q1 ⓐ).
					  화면의 4값은 `status === 'PLANNED' ? readiness : status`로 만든다
					- startDate / endDate: 프로젝트 기간. **endDate는 날짜만이며 시각 의미가 없다**(9차 Q2)
					- curriculumCount / conceptCount / conceptCandidateCount: 목록 응답과 같은 세 숫자

					**응답 (200) — 회차 시각 넷**(22차 R5·R9)

					개요 타임라인이 **규칙 문장 대신 실제 시각**을 그리는 근거다. 여태 운영자 화면은
					`응시 창: 코드 분석 완료 시점부터 24시간`처럼 규칙만 말했는데, 교육생은 자기 홈에서
					그 시각을 정확히 보고 있었다 — 문의를 받는 사람이 정작 시각을 몰랐다.

					| 필드 | 무엇 | 언제 null인가 |
					|---|---|---|
					| `submissionDueAt` | **실제 제출 마감.** `endDate`가 아니다 | 회차가 없는 프로젝트(22차 이전 생성) |
					| `roundAssessmentOpenAt` | 회차 응시 창이 열리는 시각 | 회차가 열리기 전(코드 분석 전) |
					| `roundAssessmentDueAt` | 회차 응시 창이 닫히는 시각 | 〃 |
					| `reportPublishNotBeforeAt` | 리포트 발행 하한 | 응시가 닫히기 전 |

					**개인별 시각은 여기 없다.** `assessmentOpenAt`·`assessmentCloseAt`은 사람마다 다른
					값이라 교육생 홈(`CurrentRoundResponse`)과 명단에 있다. 운영자에게 필요한 것은
					회차의 창이다.

					**응답 (200) — 되읽기**

					| 필드 | 무엇 | 쓰기 경로 |
					|---|---|---|
					| `curricula[]` | 연결된 교안. 순서는 연결 순서(sequenceNo) | `POST /projects/{id}/curricula` |
					| `concepts[]` | 확정된 검증 개념. 순서는 확정 순서 | `PUT /projects/{id}/concepts` |
					| `requirementTitles[]` | 요구사항 문구 배열 | `PUT /projects/{id}/requirements` |

					**`curricula[]` 항목** — projectCurriculumId · curriculumVersionId · materialId ·
					originalFileName · versionNo · linkedAt

					**`concepts[]` 항목** — mappingId · teachesId · extractedName · curriculumVersionId ·
					pageStart · pageEnd. `curriculumVersionId`·페이지는 **확정 뒤에도** 계속 쓰이는 값이다 —
					구성 탭이 개념마다 `· spring_backend_v1 v1 · p.53`을 쓰고 리포트·면담 브리프가
					그 값으로 교안 위치를 가리킨다.

					**저장 직후 이 API를 다시 부르면 화면을 갱신할 수 있다.** `PUT /concepts`·`PUT /requirements`·
					`POST /curricula`는 본문을 돌려주지 않으므로, 저장 후 상태는 여기서 읽는다.

					**아직 확정·연결하지 않았으면 빈 배열이다.** null이 아니라 `[]`이며, 세 배열 모두 키는 항상 온다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "프로젝트 상세 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음(다른 기관의 프로젝트도 여기로 온다)"),
	})
	@GetMapping("/projects/{projectId}")
	public ResponseEntity<ProjectDetailResponse> findProject(
			@Parameter(description = "프로젝트 ID") @PathVariable UUID projectId
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		return ResponseEntity.ok(ProjectDetailResponse.from(projectService.findProjectDetail(projectId, orgId)));
	}

	@Operation(
			summary = "프로젝트 일정 수정 | ✅ 사용 가능",
			description = """
					프로젝트의 시작일·종료일을 변경한다.

					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- startDate / endDate (필수): 새 프로젝트 기간

					**응답 (200)**
					- 변경된 프로젝트 정보(응답 필드는 "프로젝트 목록"의 항목과 동일)

					## ⚠️ `endDate`에는 **시각 의미가 없다** (9차 Q2의 답)

					서버는 받은 날짜를 `date` 그대로 저장한다. **`23:59:59 KST`로 해석하지 않으며,
					다른 어떤 시각으로도 해석하지 않는다** — `project.end_date`는 `DATE` 컬럼이고
					이 경로에는 시간대 변환이 없다.

					**학생에게 나가는 실제 제출 마감은 다른 값이다.**

					| | 값 | 어디 |
					|---|---|---|
					| 이 API가 쓰는 것 | `project.end_date` (`date`) | 회차 기간 표시용 |
					| 실제 마감 | `project_assessment_round.submission_due_at` (`timestamptz`) | **목록·상세의 `submissionDueAt`**(22차 R5) · `class-progress`의 `submissionDueAt` |

					> 22차 R5로 **목록(`ProjectResponse`)과 상세(`ProjectDetailResponse`)가 이 값을 직접 싣는다.**
					> 마감을 그리려고 `class-progress`를 부를 필요가 없어졌다 — 그쪽은 현황 탭의 조회라
					> 개념이 확정되기 전이나 회차가 열리기 전에는 쓸 수 없었다.

					**둘은 서버에서 연결돼 있지 않다.** 이 API로 `endDate`를 바꿔도 `submissionDueAt`은
					움직이지 않는다. 두 값이 `2027-02-26` ↔ `2027-02-26T14:59:00Z`(= KST 23:59)로 맞아
					보이는 것은 **현재 데이터가 그렇게 들어가 있어서**이지 규칙이 아니다.

					따라서 화면이 `endDate`에 `23:59`을 붙여 마감으로 표시하면, 운영자가 일정을 수정한
					순간 **학생에게 알려준 마감과 실제 마감이 갈린다.** 마감 시각을 보여줘야 하는 자리에서는
					`class-progress`의 `submissionDueAt`을 쓰는 것이 맞다.

					## 🔴 18차 R5로 정해졌다 — 마감 시각을 이 API가 받는다

					**`submissionDueAt`(선택)** 을 함께 보내면 그 회차의 `submission_due_at`을 같이 바꾼다.
					9차 Q2에서 열어 둔 질문을 프론트가 ⓑ(별도 필드)로 답해 그대로 구현했다.

					| 보낸 것 | 결과 |
					|---|---|
					| `startDate`·`endDate`만 | 기간만 바뀐다. **마감은 그대로** |
					| + `submissionDueAt` | 기간과 마감이 함께 바뀐다 |

					**여전히 서버가 둘을 자동으로 연결하지 않는다.** 기간을 늘려도 마감은 움직이지
					않는다 — 회차 기간은 운영 일정이고 제출 마감은 학생과의 약속이라 같이 움직여야 할
					이유가 없고, 자동 파생을 넣으면 운영자가 기간만 손댔을 때 **이미 알린 마감이 조용히
					바뀐다.**

					그래서 화면이 마감을 표시할 때는 여전히 `endDate`에 `23:59`을 붙이지 말고
					실제 마감 값을 써야 한다.

					시각대는 UTC로 저장된다 — `23:59 KST`는 `T14:59:00Z`다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "프로젝트 일정 수정 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락 또는 종료일이 시작일보다 빠름(fieldErrors 동봉)"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
	})
	@PatchMapping("/projects/{projectId}")
	public ResponseEntity<ProjectResponse> updateSchedule(
			@Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
			@Valid @RequestBody UpdateProjectScheduleRequest request
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		Project project = projectService.updateSchedule(projectId, orgId,
				request.startDate(), request.endDate(), request.submissionDueAt(), actorUserId);
		return ResponseEntity.ok(ProjectResponse.from(projectService.summarize(project, orgId)));
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
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED requirementTitles 키가 없음 — 전부 지우려면 빈 배열을 보낸다"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
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
			@ApiResponse(responseCode = "400", description = "CURRICULUM_NOT_APPLICABLE_TO_BIG_PROJECT 빅프로젝트다(연결 UI를 감춘다) · CURRICULUM_ANALYSIS_NOT_SUCCEEDED 성공한 분석이 없다(재분석 안내) · CURRICULUM_MAPPING_NOT_APPROVED 승인된 개념 매핑이 없다(개념 승인 화면으로) · VALIDATION_FAILED curriculumVersionId 누락"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트가 없음 · CURRICULUM_VERSION_NOT_FOUND 교안 버전이 없음"),
			@ApiResponse(responseCode = "409", description = "CURRICULUM_ALREADY_LINKED 이미 연결된 교안 버전 — 새로고침이 아니라 그 항목을 비활성화한다"),
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
			operationId = "unlinkCurriculum",
			summary = "프로젝트 교안 연결 해제 | ✅ 사용 가능",
			description = """
					프로젝트에 붙인 교안을 뗀다(9차 R4). 교안 변경 모달이 체크박스로 교안을 켜고 끄는데
					끄는 쪽이 없어 **한 번 잘못 붙이면 되돌릴 수 없었다.**

					**요청**
					- projectId (경로): 대상 프로젝트 ID
					- projectCurriculumId (경로): 해제할 연결 ID.
					  `GET /projects/{projectId}`의 `curricula[].projectCurriculumId`다

					**응답 (204)** — 본문 없음

					## 확정된 검증 개념이 쓰는 교안은 뗄 수 없다

					409 `CURRICULUM_IN_USE_BY_CONCEPTS`. **출처가 끊긴 개념은 리포트가 교안 위치를
					가리킬 수 없다** — 화면의 `canUnlinkCurriculum`과 같은 규칙이며, 화면만 막으면 우회된다.

					화면이 할 일은 검증 개념을 먼저 다시 확정하도록 안내하는 것이다.

					💡 **활성(ACTIVE) 개념 세트만 본다.** 교체되어 이력으로만 남은 과거 세트까지 보면
					한 번 개념에 쓰인 교안을 영영 뗄 수 없게 되어, 잘못 붙인 교안을 되돌릴 방법이 사라진다.

					⚠️ **연결 행은 실제로 지운다.** project_curriculum에 소프트 삭제 컬럼이 없어
					붙였다 뗀 기록은 남지 않는다. 그 이력이 필요해지면 알려 주시면 컬럼을 추가하겠다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "교안 연결 해제 성공(본문 없음)"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트가 없음 · CURRICULUM_LINK_NOT_FOUND 이 프로젝트의 교안 연결이 아님(이미 해제됐거나 다른 프로젝트의 연결 ID)"),
			@ApiResponse(responseCode = "409", description = "CURRICULUM_IN_USE_BY_CONCEPTS 확정된 검증 개념이 이 교안에서 왔음 — 개념을 먼저 다시 확정해야 한다"),
	})
	@DeleteMapping("/projects/{projectId}/curricula/{projectCurriculumId}")
	public ResponseEntity<Void> unlinkCurriculum(
			@Parameter(description = "프로젝트 ID") @PathVariable UUID projectId,
			@Parameter(description = "해제할 연결 ID(상세 응답의 curricula[].projectCurriculumId)")
			@PathVariable UUID projectCurriculumId
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		projectService.unlinkCurriculum(projectId, orgId, projectCurriculumId, actorUserId);
		return ResponseEntity.noContent().build();
	}

	@Operation(
			operationId = "deleteProject",
			summary = "프로젝트(회차) 삭제 | ✅ 사용 가능",
			description = """
					회차를 삭제한다(9차 R4). 데이터를 지우지 않고 삭제 시각만 기록하는 **소프트 삭제**이며,
					목록·상세·회차 목록에서 곧바로 빠진다.

					**요청**
					- projectId (경로): 삭제할 프로젝트 ID

					**응답 (204)** — 본문 없음

					요구사항·검증개념·교안 연결은 지우지 않고 그대로 둔다 — 되살릴 일이 생겼을 때 되돌릴 수 있고,
					과거 이력이 가리키는 대상이 사라지지 않는다.

					## 삭제 가능 조건은 서버가 판정한다

					| 막는 조건 | 코드 |
					|---|---|
					| 이 회차의 팀이 낸 **제출**이 있다 | 409 `PROJECT_NOT_DELETABLE` |
					| 이 회차의 **응시 기록**이 있다 | 409 `PROJECT_NOT_DELETABLE` |

					화면도 `rules.ts`의 `canDelete`로 막지만 **클라이언트 검증만 있으면 우회되고,
					그때 사라지는 것은 학생이 실제로 한 제출·분석·응시**다.

					어느 쪽이 걸렸는지는 `message`에 담는다 — 화면이 할 일은 "지울 수 없습니다"를 보여주고
					버튼을 잠그는 하나라서 코드를 쪼개지 않았다.

					## ⚠️ 삭제한 회차의 **이름과 순번은 다시 쓸 수 없다**

					DB의 `uq_project_cohort_id_name`·`uq_project_cohort_id_sequence_no`가 부분 인덱스가 아니라
					(`WHERE deleted_at IS NULL`이 없다) 소프트 삭제된 행도 그 이름·순번을 계속 점유한다.

					- 지운 회차와 **같은 이름으로 다시 만들면 409** `PROJECT_NAME_DUPLICATED`다
					- 새 회차의 `sequenceNo`는 지운 회차의 번호를 건너뛴다(6번을 지우면 다음은 7번)

					화면의 생성 모달이 "같은 이름을 다시 쓸 수 없습니다"를 안내해 주시면 좋겠다.
					이 제약을 풀려면 DDL의 두 UNIQUE를 부분 인덱스로 바꿔야 해서, 필요하시면 알려 주세요.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "프로젝트 삭제 성공(본문 없음)"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음(이미 삭제된 회차도 여기로 온다)"),
			@ApiResponse(responseCode = "409", description = "PROJECT_NOT_DELETABLE 제출 또는 응시 기록이 있는 회차 — 무엇이 붙어 있는지는 message에 있다"),
	})
	@DeleteMapping("/projects/{projectId}")
	public ResponseEntity<Void> deleteProject(
			@Parameter(description = "삭제할 프로젝트 ID") @PathVariable UUID projectId
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		projectService.deleteProject(projectId, orgId, actorUserId);
		return ResponseEntity.noContent().build();
	}

	@Operation(
			summary = "검증개념 후보 조회 | ✅ 사용 가능",
			description = """
					프로젝트에 연결된 교안들의 승인된 매핑 전체를 검증개념 후보로 내려준다.

					**요청**
					- projectId (경로): 대상 프로젝트 ID

					**응답 (200)**

					| 필드 | 설명 |
					|---|---|
					| `mappingId` | 매핑 ID. 검증개념 확정 시 이 ID를 보낸다 |
					| `teachesId` | 공용 개념 원장 ID |
					| `extractedName` | 항목 이름 |
					| `description` | 정의문. `definitionMissing`이 true면 **null** |
					| `definitionMissing` | 정의문이 아직 추출되지 않았으면 true |
					| `curriculumVersionId` | 이 후보가 나온 교안 버전 — **후보를 묶는 기준** |
					| `sectionId` · `sectionTitle` | 섹션 식별자 / 섹션 헤더 문구. 섹션 정보가 없는 매핑이면 null |
					| `pageStart` · `pageEnd` | `p.53` 표기. 확정 뒤에도 계속 쓰인다 |

					**항목 모양을 `GET /curricula/{materialId}/sections`의 항목과 맞췄다**(9차 R2).
					같은 원장(curriculum_teaches_mapping)에서 나오는 값인데 이쪽만 좁으면, 후보를 교안·섹션별로
					묶어 그리는 화면이 묶을 기준을 잃고 후보 12건이 평평한 한 덩어리가 된다 —
					그러면 `p.55`가 어느 교안의 55쪽인지 알 수 없다.

					**참고** — 프로젝트에 연결된 교안(project_curriculum)이 없으면 항상 빈 배열이다.
					`POST /projects/{projectId}/curricula`로 먼저 교안을 연결해야 한다.
					개수만 필요하면 `GET /projects/{projectId}`의 `conceptCandidateCount`를 쓴다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "검증개념 후보 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
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
			@ApiResponse(responseCode = "400", description = "CONCEPT_MAPPING_NOT_FOUND 존재하지 않는 매핑 ID가 포함됨(후보 목록이 낡았다 — 다시 읽는다) · VALIDATION_FAILED mappingIds가 비었음"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 프로젝트를 찾을 수 없음"),
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
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 기준 프로젝트를 찾을 수 없음"),
	})
	@GetMapping("/projects/{projectId}/rounds")
	public ResponseEntity<List<ProjectResponse>> findRounds(
			@Parameter(description = "기준 프로젝트 ID(같은 기수의 회차 전체를 조회)") @PathVariable UUID projectId
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		Project baseProject = projectService.findProject(projectId, orgId);
		List<ProjectResponse> response = projectService.findProjectSummaries(baseProject.getCohortId(), orgId).stream()
				.filter(summary -> summary.project().getProjectCategory() == ProjectCategory.MINI_PROJECT)
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
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락 또는 종료일이 시작일보다 빠름(fieldErrors 동봉)"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 회차(프로젝트)를 찾을 수 없음"),
	})
	@PatchMapping("/projects/{projectId}/rounds/{roundId}")
	public ResponseEntity<ProjectResponse> updateRoundSchedule(
			@Parameter(description = "기준 프로젝트 ID") @PathVariable UUID projectId,
			@Parameter(description = "회차 ID(=projectId와 동일하게 취급)") @PathVariable UUID roundId,
			@Valid @RequestBody UpdateProjectScheduleRequest request
	) {
		UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		Project project = projectService.updateSchedule(roundId, orgId,
				request.startDate(), request.endDate(), request.submissionDueAt(), actorUserId);
		return ResponseEntity.ok(ProjectResponse.from(projectService.summarize(project, orgId)));
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
					| `submissionDueAt` | timestamp | 제출 마감 시각. **학생에게 나가는 실제 마감이 이 값이다** — 회차의 `endDate`(날짜만)와는 서버에서 연결돼 있지 않다(9차 Q2) |
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

					## 22차 R6 — `PLANNED` 회차도 200이다

					**상태로 막지 않는다.** `PLANNED`·`RUNNING`·`CLOSED` 어느 쪽이든 회차가 있으면
					200이며, 아직 제출이 없으면 `classes[]`가 비고 `summary`가 전부 0으로 나간다 —
                    화면은 그것을 「아직 제출한 학생이 없습니다」로 그리면 된다.

					답하지 못하던 것은 **회차가 없는 프로젝트**였다. 22차 이전에는 프로젝트를 만들어도
					`project_assessment_round`를 만들지 않아, 화면에서 만든 회차에는 회차 행이 아예
					없었다. 지금은 생성이 회차를 함께 만든다(`POST /cohorts/{cohortId}/projects` 참고).

					그때 만들어져 회차가 없는 프로젝트는 **`PROJECT_ROUND_NOT_CREATED`(404)** 로 답한다.
					`PROJECT_ROUND_NOT_FOUND`와 나눈 이유는 화면이 할 일이 다르기 때문이다 —
					이쪽은 「회차 준비 중」이고, 그쪽은 없는 번호를 물은 것이라 드롭다운을 되돌려야 한다.
					"""
	)
	@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "반별 현황 조회 성공"),
			@ApiResponse(responseCode = "400", description = "ROUND_NO_INVALID 회차 번호가 1 미만"),
			@ApiResponse(responseCode = "401", description = "ANALYTICS_VIEWER_NOT_FOUND 토큰은 유효하지만 계정을 찾을 수 없음"),
			@ApiResponse(responseCode = "403", description = "ANALYTICS_VIEWER_NOT_ACTIVE 활성 계정 아님 · ANALYTICS_ORGANIZATION_NOT_ACTIVE 소속 기관이 활성 아님 · ANALYTICS_ROLE_NOT_ALLOWED 오퍼레이터·매니저가 아님 · PROJECT_CROSS_ORGANIZATION 다른 기관의 프로젝트"),
			@ApiResponse(responseCode = "404", description = "PROJECT_ROUND_NOT_FOUND 그 프로젝트에 그 번호의 회차가 없음 · PROJECT_ROUND_NOT_CREATED 회차가 아직 하나도 없음(22차 R6)")
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