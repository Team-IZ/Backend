package com.bigproject.backend.domain.academicoperations.presentation;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.academicoperations.application.CohortService;
import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CohortListResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CohortResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CreateCohortRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.EndCohortRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.UpdateCohortRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Academic Operations", description = "기수, 반, 교육생 소속 이력, 매니저 반 배정 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping(value = "/cohorts", produces = MediaType.APPLICATION_JSON_VALUE)
public class CohortController {

	private final CohortService cohortService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
			operationId = "findCohorts",
			summary = "기관 기수 목록 조회 | ✅ 사용 가능",
			description = """
					로그인한 사용자의 소속 기관에 개설된 기수를 상태 필터·이름 검색으로 페이지네이션 조회한다.
					조회 범위인 기관은 요청 파라미터가 아니라 액세스 토큰에서 가져온다 — 다른 기관의 기수는 조회할 수 없다.

					**요청**
					- status (쿼리, 선택): 기수 상태 필터. PLANNED/RUNNING/CLOSED. 생략하면 전체
					- query (쿼리, 선택): 기수명 부분검색
					- page (쿼리, 선택, 기본 0): 0부터 시작하는 페이지 번호
					- size (쿼리, 선택, 기본 20, 최대 100): 페이지당 개수

					**응답 (200)**
					- content[]: 기수 목록(필드는 아래 "기수 상세 조회" 응답과 동일)
					- page / size: 요청한 페이지 정보 그대로
					- totalElements: 조건에 맞는 전체 기수 수
					- totalPages: 전체 페이지 수

					content[].traineeCount는 **재적 교육생 수**다 — 이 기수에 등록돼 있고 아직 나가지 않은 인원이며,
					중도 이탈자는 빠진다(10차 R3). 그래서 교육생 명단 조회의 전체 건수보다 작을 수 있다.

					content[].classroomCount는 **반 개수**이며 삭제된 반은 빠진다(13차 Q1).
					화면의 `10반 250명`이 이 둘이다 — 반 개수 때문에 기수마다
					`GET /cohorts/{cohortId}/classrooms`를 부르지 않아도 된다.
					둘 다 이 페이지의 기수 전체를 한 번에 세므로 기수가 늘어도 조회가 늘지 않는다.

					**아직 채워지지 않는 값** — content[].managers는 항상 빈 배열이다.
					담당 매니저는 classroom 도메인 조인이 필요해 아직 연결되지 않았다.
					반 목록·담당 매니저가 필요하면 `GET /cohorts/{cohortId}/classrooms`를 함께 호출한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED page·size 범위가 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@GetMapping
	public ResponseEntity<CohortListResponse> findCohorts(
			@Parameter(description = "기수 상태 필터. 생략하면 전체")
			@RequestParam(required = false) CohortStatus status,

			@Parameter(description = "기수명 부분검색", example = "7기")
			@RequestParam(required = false) String query,

			@Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
			@RequestParam(defaultValue = "0") @Min(0) int page,

			@Parameter(description = "페이지당 개수(최대 100)", example = "20")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,

			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);

		Page<Cohort> cohorts = cohortService.findCohorts(
				organizationId, status, query, PageRequest.of(page, size));

		// 기수마다 COUNT를 날리지 않도록 이 페이지의 기수 전체를 한 번에 센다(10차 R3 · 13차 Q1).
		List<UUID> cohortIds = cohorts.getContent().stream().map(Cohort::getCohortId).toList();
		Map<UUID, Integer> traineeCounts = cohortService.countActiveTrainees(cohortIds, organizationId);
		Map<UUID, Integer> classroomCounts = cohortService.countClassrooms(cohortIds, organizationId);

		CohortListResponse response = new CohortListResponse(
				cohorts.getContent().stream()
						.map(cohort -> CohortResponse.from(
								cohort,
								traineeCounts.getOrDefault(cohort.getCohortId(), 0),
								classroomCounts.getOrDefault(cohort.getCohortId(), 0)))
						.toList(),
				cohorts.getNumber(),
				cohorts.getSize(),
				cohorts.getTotalElements(),
				cohorts.getTotalPages()
		);
		return ResponseEntity.ok(response);
	}

	@Operation(
			operationId = "findCohort",
			summary = "기수 상세 조회 | ✅ 사용 가능",
			description = """
					기수 하나의 상세 정보를 조회한다. 조회 범위인 기관은 액세스 토큰에서 가져오며,
					다른 기관의 기수를 요청하면 404다(존재 여부 자체를 알려주지 않기 위해 403이 아니라 404로 응답한다).
					삭제된 기수도 404다.

					**요청**
					- cohortId (경로): 조회할 기수 ID

					**응답 (200)**
					- cohortId: 기수 ID
					- organizationId: 소속 기관 ID
					- name: 기수명
					- status: PLANNED(개설 예정) / RUNNING(진행 중) / CLOSED(종료)
					- startDate / endDate: 기수 기간
					- traineeCount: 재적 교육생 수(등록돼 있고 아직 나가지 않은 인원. 중도 이탈자 제외)
					- classroomCount: 반 개수(삭제된 반 제외) — 13차 Q1
					- managers[]: 담당 매니저 목록

					**아직 채워지지 않는 값** — managers는 항상 빈 배열이다(목록 조회와 동일).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 상세 조회 성공"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수·삭제된 기수 포함)"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@GetMapping("/{cohortId}")
	public ResponseEntity<CohortResponse> findCohort(
			@Parameter(description = "조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		Cohort cohort = cohortService.findCohort(cohortId, organizationId);
		return ResponseEntity.ok(toResponse(cohort, cohortId, organizationId));
	}

	@Operation(
			operationId = "createCohort",
			summary = "기수 생성 | ✅ 사용 가능",
			description = """
					오퍼레이터가 자기 기관에 새 기수를 개설한다. 개설 시 기관의 활성 운영 정책에서
					기본 공개범위(defaultDisclosureScope)를 복사해 기수에 고정한다 — 이후 기관 정책이 바뀌어도
					이미 만들어진 기수의 공개범위는 따라 바뀌지 않는다.

					**요청**
					- organizationId (필수): 기수를 개설할 기관 ID. 액세스 토큰의 기관과 다르면 403
					- name (필수): 기수명. 같은 기관 안에서 중복되면 409
					- startDate (필수) / endDate (필수): 기수 기간. endDate가 startDate보다 빠르면 400

					**응답 (201)**
					- 생성된 기수 정보(응답 필드는 "기수 상세 조회"와 동일). status는 PLANNED로 시작한다

					⚠️ **11차 Q3 — `initialTrainees`를 요청 스키마에서 뺐다.** 저장되지 않는 필드를 남겨 두면
					화면이 "보내도 아무 일이 안 일어나는 칸"을 그리게 된다. 교육생 등록은
					`POST /cohorts/{cohortId}/trainees`(CSV) 또는 `.../trainees/invitations`(직접 입력)로 한다 —
					행별 실패를 돌려줘야 해서 기수 생성 응답에 얹기에 맞지 않는다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "기수 생성 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 필수값 누락 · COHORT_PERIOD_INVALID 종료일이 시작일보다 빠름"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터가 아니거나 다른 기관의 organizationId를 지정함"),
			@ApiResponse(responseCode = "409",
					description = "COHORT_NAME_TAKEN 같은 기관에 이미 존재하는 기수명 · ORG_POLICY_NOT_FOUND 기관에 활성 운영 정책이 없음"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PostMapping
	public ResponseEntity<CohortResponse> createCohort(
			@Valid @RequestBody CreateCohortRequest request,
			Authentication authentication
	) {
		verifyOrganizationScope(request.organizationId(), authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();

		Cohort cohort = cohortService.createCohort(
				request.organizationId(),
				request.name(),
				request.startDate(),
				request.endDate(),
				actorUserId
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(CohortResponse.from(cohort));
	}

	@Operation(
			operationId = "endCohort",
			summary = "기수 종료 | ✅ 사용 가능",
			description = """
					진행 중인 기수를 CLOSED로 전환한다. 종료 대상 기관은 액세스 토큰에서 가져오므로
					다른 기관의 기수를 종료할 수 없다(404).

					**요청**
					- cohortId (경로): 종료할 기수 ID
					- reason (필수): 종료 사유. 빈 문자열이면 400

					**응답 (200)**
					- 종료 처리된 기수 정보(응답 필드는 "기수 상세 조회"와 동일). status가 CLOSED로 바뀐다

					종료는 삭제가 아니다. 명단·과거 이력은 그대로 남고 새 활동만 막힌다. 이미 종료된 기수를
					다시 종료하면 아무 변화 없이 그대로 성공 응답한다(멱등).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 종료 성공"),
			@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED 종료 사유가 비어 있음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수 포함)")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{cohortId}/end")
	public ResponseEntity<CohortResponse> endCohort(
			@Parameter(description = "종료할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			@Valid @RequestBody EndCohortRequest request,
			Authentication authentication
	) {
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		Cohort cohort = cohortService.closeCohort(cohortId, extractOrganizationId(authentication), actorUserId);
		return ResponseEntity.ok(CohortResponse.from(cohort));
	}

	@Operation(
			operationId = "updateCohort",
			summary = "기수 수정 | ✅ 사용 가능",
			description = """
					기수의 이름·기간을 고친다(11차 Q2). **개강 전(`PLANNED`)에만 열려 있다.**

					지금까지 기수는 만들고 종료하는 것만 있어서 **기수명 오타 하나를 고칠 수 없었다.**
					반은 9차 R6로 수정·삭제가 생겼는데 기수에는 없던 자리다.

					**요청** — 세 필드 모두 선택이며 **보낸 것만 바뀐다**(반 수정과 같은 규칙).
					- name (선택): 새 기수명. 같은 기관 안에서 중복되면 409. 공백만 보내면 "안 바꾼다"로 본다
					- startDate / endDate (선택): 새 기간

					**셋 다 생략하면 400** `COHORT_UPDATE_EMPTY`다 — 아무 일도 하지 않는 요청이 200으로
					돌아오면 화면은 저장됐다고 오해한다.

					## 개강 후를 막는 이유

					기간은 이미 발행된 리포트와 회차 일정의 기준이다. 개강 후에 바꾸면 그것들이 가리키는
					기간과 어긋난다. 진행 중·종료된 기수는 409 `COHORT_NOT_MUTABLE`이다.

					**응답 (200)** — 수정 후의 기수 한 건(상세 조회와 같은 모양).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "수정 성공"),
			@ApiResponse(responseCode = "400", description = "COHORT_UPDATE_EMPTY 바꿀 값이 없음 · COHORT_PERIOD_INVALID 종료일이 시작일보다 빠름"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없거나 다른 기관의 기수"),
			@ApiResponse(responseCode = "409", description = "COHORT_NOT_MUTABLE 개강한 기수 · COHORT_NAME_TAKEN 이미 있는 기수명"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@PatchMapping("/{cohortId}")
	public ResponseEntity<CohortResponse> updateCohort(
			@Parameter(description = "수정할 기수 ID") @PathVariable UUID cohortId,
			@Valid @RequestBody UpdateCohortRequest request,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		Cohort cohort = cohortService.updateCohort(
				cohortId, organizationId, request.name(), request.startDate(), request.endDate(), actorUserId);
		return ResponseEntity.ok(toResponse(cohort, cohortId, organizationId));
	}

	@Operation(
			operationId = "deleteCohort",
			summary = "기수 삭제 | ✅ 사용 가능",
			description = """
					잘못 만든 기수를 되돌린다(11차 Q2). **개강 전이고 아무것도 붙지 않은 기수만** 지운다.

					운영 중인 기수를 정리하는 수단이 아니다 — 그쪽은 `PATCH /cohorts/{cohortId}/end`(종료)다.

					## 서버가 판정한다

					반 삭제(9차 R6)와 같은 방식이다. 화면도 `PLANNED`에서만 버튼을 열겠지만
					**클라이언트 검증만 있으면 우회된다.** 상태만 보고 열어 주지도 않는다 — 상태는 운영자가
					손으로 바꾸는 값이라 개강 전으로 되돌려 두고 지울 수 있기 때문이다.

					| 막는 조건 | 이유 |
					|---|---|
					| 상태가 `PLANNED`가 아님 | 개강했거나 종료된 기수다 |
					| 등록된 교육생이 있음 | **이탈자도 센다** — 지나간 등록도 사실이다 |
					| 만들어진 반이 있음 | 지운 기수를 가리키는 반이 남는다 |
					| 만들어진 회차가 있음 | 회차·제출·리포트가 끊긴다 |

					전부 409 `COHORT_NOT_DELETABLE` 하나로 답한다 — 어느 쪽이든 화면이 할 일은
					"지울 수 없습니다"를 보여주고 버튼을 잠그는 것 하나다. **무엇이 걸렸는지는 `message`에 담는다**
					(예: `등록된 교육생·만들어진 반이(가) 있어 삭제할 수 없습니다`).

					**소프트 삭제다.** 행은 남고 목록·조회에서만 빠진다.

					**응답 (204)** — 본문 없음.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "204", description = "삭제 성공"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없거나 다른 기관의 기수"),
			@ApiResponse(responseCode = "409", description = "COHORT_NOT_DELETABLE 개강했거나 명단·반·회차가 붙어 있음"),
			@ApiResponse(responseCode = "500", description = "ORGANIZATION_CONTEXT_MISSING 인증 정보에서 organizationId를 확인할 수 없음")
	})
	@PreAuthorize("hasRole('OPERATOR')")
	@DeleteMapping("/{cohortId}")
	public ResponseEntity<Void> deleteCohort(
			@Parameter(description = "삭제할 기수 ID") @PathVariable UUID cohortId,
			Authentication authentication
	) {
		UUID organizationId = extractOrganizationId(authentication);
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		cohortService.deleteCohort(cohortId, organizationId, actorUserId);
		return ResponseEntity.noContent().build();
	}

	/** 기수 한 건 응답. 재적 인원·반 개수를 함께 채운다(10차 R3 · 13차 Q1). */
	private CohortResponse toResponse(Cohort cohort, UUID cohortId, UUID organizationId) {
		List<UUID> ids = List.of(cohortId);
		return CohortResponse.from(
				cohort,
				cohortService.countActiveTrainees(ids, organizationId).getOrDefault(cohortId, 0),
				cohortService.countClassrooms(ids, organizationId).getOrDefault(cohortId, 0));
	}

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ApiException(AcademicOperationsErrorCode.ORGANIZATION_CONTEXT_MISSING);
		}
		return organizationId;
	}

	private void verifyOrganizationScope(UUID requestedOrganizationId, Authentication authentication) {
		if (!extractOrganizationId(authentication).equals(requestedOrganizationId)) {
			throw new AccessDeniedException("다른 기관의 기수에 접근할 수 없습니다.");
		}
	}
}