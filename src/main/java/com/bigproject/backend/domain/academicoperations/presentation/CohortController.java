package com.bigproject.backend.domain.academicoperations.presentation;

import com.bigproject.backend.domain.academicoperations.application.CohortService;
import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CohortListResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CohortResponse;
import com.bigproject.backend.domain.academicoperations.presentation.dto.CreateCohortRequest;
import com.bigproject.backend.domain.academicoperations.presentation.dto.EndCohortRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Tag(name = "Academic Operations", description = "기수, 반, 교육생 소속 이력, 매니저 반 배정 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/cohorts")
public class CohortController {

	private final CohortService cohortService;
	private final CurrentUserResolver currentUserResolver;

	@Operation(
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

					**아직 채워지지 않는 값** — content[].traineeCount는 항상 0, content[].managers는 항상 빈 배열이다.
					교육생 수와 담당 매니저는 member·classroom 도메인 조인이 필요해 아직 연결되지 않았다.
					반 목록·담당 매니저가 필요하면 `GET /cohorts/{cohortId}/classrooms`를 함께 호출한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 목록 조회 성공"),
			@ApiResponse(responseCode = "400", description = "page·size 범위가 올바르지 않음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "500", description = "인증 정보에서 organizationId를 확인할 수 없음")
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

		CohortListResponse response = new CohortListResponse(
				cohorts.getContent().stream().map(CohortResponse::from).toList(),
				cohorts.getNumber(),
				cohorts.getSize(),
				cohorts.getTotalElements(),
				cohorts.getTotalPages()
		);
		return ResponseEntity.ok(response);
	}

	@Operation(
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
					- traineeCount: 소속 교육생 수
					- managers[]: 담당 매니저 목록

					**아직 채워지지 않는 값** — traineeCount는 항상 0, managers는 항상 빈 배열이다(목록 조회와 동일).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "기수 상세 조회 성공"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "404", description = "기수를 찾을 수 없음(다른 기관의 기수·삭제된 기수 포함)"),
			@ApiResponse(responseCode = "500", description = "인증 정보에서 organizationId를 확인할 수 없음")
	})
	@GetMapping("/{cohortId}")
	public ResponseEntity<CohortResponse> findCohort(
			@Parameter(description = "조회할 기수 ID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable UUID cohortId,
			Authentication authentication
	) {
		Cohort cohort = cohortService.findCohort(cohortId, extractOrganizationId(authentication));
		return ResponseEntity.ok(CohortResponse.from(cohort));
	}

	@Operation(
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

					⚠️ `initialTrainees`를 요청에 넣어도 저장되지 않는다. 스키마에는 남아 있지만 서버가 사용하지 않으며,
					교육생 등록은 `POST /cohorts/{cohortId}/trainees`(CSV) 또는 `.../trainees/invitations`(직접 입력)로 한다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "기수 생성 성공"),
			@ApiResponse(responseCode = "400", description = "필수값 누락 또는 종료일이 시작일보다 빠름"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터가 아니거나 다른 기관의 organizationId를 지정함"),
			@ApiResponse(responseCode = "409", description = "같은 기관에 이미 존재하는 기수명"),
			@ApiResponse(responseCode = "500", description = "기관에 활성 운영 정책이 없음")
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
			@ApiResponse(responseCode = "400", description = "종료 사유가 비어 있음"),
			@ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
			@ApiResponse(responseCode = "403", description = "오퍼레이터 권한이 없음"),
			@ApiResponse(responseCode = "404", description = "기수를 찾을 수 없음(다른 기관의 기수 포함)")
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

	private UUID extractOrganizationId(Authentication authentication) {
		Object details = authentication.getDetails();
		if (!(details instanceof UUID organizationId)) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"인증 정보에서 organizationId(UUID)를 확인할 수 없습니다.");
		}
		return organizationId;
	}

	private void verifyOrganizationScope(UUID requestedOrganizationId, Authentication authentication) {
		if (!extractOrganizationId(authentication).equals(requestedOrganizationId)) {
			throw new AccessDeniedException("다른 기관의 기수에 접근할 수 없습니다.");
		}
	}
}