package com.bigproject.backend.domain.cohort.presentation;

import com.bigproject.backend.domain.cohort.application.CohortService;
import com.bigproject.backend.domain.cohort.domain.Cohort;
import com.bigproject.backend.domain.cohort.domain.CohortStatus;
import com.bigproject.backend.domain.cohort.presentation.dto.CohortListResponse;
import com.bigproject.backend.domain.cohort.presentation.dto.CohortResponse;
import com.bigproject.backend.domain.cohort.presentation.dto.CreateCohortRequest;
import com.bigproject.backend.domain.cohort.presentation.dto.EndCohortRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "Cohort", description = "기관 기수 생성·조회·종료 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/cohorts")
public class CohortController {

	private final CohortService cohortService;

	// 기존 TODO("인증 연동 후 X-Actor-User-Id 헤더 제거")를 해소한 것이다.
	// feat/rbac 머지로 CurrentUserResolver가 생겼고 organization·operations가 이미 쓰고 있다.
	// 헤더 방식은 클라이언트가 임의 UUID를 보내 cohort.created_by / updated_by를 위조할 수 있었다.
	private final CurrentUserResolver currentUserResolver;

	@Operation(summary = "기관 기수 목록 조회")
	@GetMapping
	public ResponseEntity<CohortListResponse> findCohorts(
			@RequestParam(required = false) CohortStatus status,
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "0") @Min(0) int page,
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

	@Operation(summary = "기수 상세 조회")
	@GetMapping("/{cohortId}")
	public ResponseEntity<CohortResponse> findCohort(@PathVariable UUID cohortId, Authentication authentication) {
		Cohort cohort = cohortService.findCohort(cohortId, extractOrganizationId(authentication));
		return ResponseEntity.ok(CohortResponse.from(cohort));
	}

	@Operation(summary = "기수 생성")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
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
				request.educationTrack(),
				actorUserId
		);
		return ResponseEntity.status(HttpStatus.CREATED).body(CohortResponse.from(cohort));
	}

	@Operation(summary = "기수 종료")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/{cohortId}/end")
	public ResponseEntity<CohortResponse> endCohort(
			@PathVariable UUID cohortId,
			@Valid @RequestBody EndCohortRequest request,
			Authentication authentication
	) {
		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		Cohort cohort = cohortService.closeCohort(cohortId, extractOrganizationId(authentication), actorUserId);
		return ResponseEntity.ok(CohortResponse.from(cohort));
	}

	// JwtFilter가 authentication.getDetails()에 담아준 organizationId(UUID)를 추출
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