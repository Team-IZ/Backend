package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.domain.OrganizationStatus;
import com.bigproject.backend.domain.organization.presentation.dto.CreateOrganizationRequest;
import com.bigproject.backend.domain.organization.presentation.dto.DeleteOrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationListResponse;
import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import com.bigproject.backend.domain.organization.presentation.dto.UpdateOrganizationRequest;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organization", description = "기관 프로비저닝과 상태 관리 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Validated
@RestController
@RequestMapping("/organizations")
public class OrganizationController {

	@Operation(summary = "기관 목록 조회")
	@GetMapping
	public ResponseEntity<OrganizationListResponse> findOrganizations(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) OrganizationStatus status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기관 생성 및 기본 운영 정책 초기화")
	@PostMapping
	public ResponseEntity<OrganizationResponse> createOrganization(
			@Valid @RequestBody CreateOrganizationRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기관 상세 조회")
	@GetMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> findOrganization(@PathVariable Long organizationId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기관 이름 또는 운영 상태 변경")
	@PatchMapping("/{organizationId}")
	public ResponseEntity<OrganizationResponse> updateOrganization(
			@PathVariable Long organizationId,
			@Valid @RequestBody UpdateOrganizationRequest request
	) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@Operation(summary = "기관 soft-delete")
	@DeleteMapping("/{organizationId}")
	public ResponseEntity<DeleteOrganizationResponse> deleteOrganization(@PathVariable Long organizationId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
