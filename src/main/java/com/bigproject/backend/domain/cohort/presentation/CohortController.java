package com.bigproject.backend.domain.cohort.presentation;

import com.bigproject.backend.domain.cohort.domain.CohortStatus;
import com.bigproject.backend.domain.cohort.presentation.dto.CohortListResponse;
import com.bigproject.backend.domain.cohort.presentation.dto.CohortResponse;
import com.bigproject.backend.domain.cohort.presentation.dto.CreateCohortRequest;
import com.bigproject.backend.domain.cohort.presentation.dto.EndCohortRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cohort", description = "기관 기수 생성·조회·종료 API")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping("/cohorts")
public class CohortController {

	@Operation(summary = "기관 기수 목록 조회")
	@GetMapping
	public ResponseEntity<CohortListResponse> findCohorts(
			@RequestParam Long organizationId,
			@RequestParam(required = false) CohortStatus status,
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기수 상세 조회")
	@GetMapping("/{cohortId}")
	public ResponseEntity<CohortResponse> findCohort(@PathVariable Long cohortId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기수 생성")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PostMapping
	public ResponseEntity<CohortResponse> createCohort(@Valid @RequestBody CreateCohortRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기수 종료")
	@PreAuthorize("hasRole('LEAD_MANAGER')")
	@PatchMapping("/{cohortId}/end")
	public ResponseEntity<CohortResponse> endCohort(
			@PathVariable Long cohortId,
			@Valid @RequestBody EndCohortRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
