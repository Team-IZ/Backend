package com.bigproject.backend.domain.operations.presentation;

import com.bigproject.backend.domain.operations.application.OperationsService;
import com.bigproject.backend.domain.operations.presentation.dto.OperationSettingResponse;
import com.bigproject.backend.domain.operations.presentation.dto.OrganizationUsageResponse;
import com.bigproject.backend.domain.operations.presentation.dto.UpdateOperationSettingRequest;
import com.bigproject.backend.global.security.SystemRequester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.UUID;

@Tag(name = "Operations", description = "기관 사용량·AI 비용·운영 설정 API")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RestController
@RequestMapping("/organizations/{organizationId}/operations")
@RequiredArgsConstructor
public class OperationsController {

	private final OperationsService operationsService;

	@Operation(summary = "기관 월별 저장량·활동·AI 비용 조회")
	@GetMapping("/usage")
	public ResponseEntity<OrganizationUsageResponse> findUsage(
			@PathVariable UUID organizationId,
			@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth period
	) {
		return ResponseEntity.ok(operationsService.findUsage(organizationId, period));
	}

	@Operation(summary = "기관 운영 설정 조회")
	@GetMapping("/settings")
	public ResponseEntity<OperationSettingResponse> findSettings(@PathVariable UUID organizationId) {
		return ResponseEntity.ok(operationsService.findSettings(organizationId));
	}

	@Operation(summary = "기관 운영 설정 변경")
	@PutMapping("/settings")
	public ResponseEntity<OperationSettingResponse> updateSettings(
			@PathVariable UUID organizationId,
			@Valid @RequestBody UpdateOperationSettingRequest request
	) {
		OperationSettingResponse response = operationsService.updateSettings(
				organizationId, request, SystemRequester.SYSTEM_REQUESTER_ID
		);
		return ResponseEntity.ok(response);
	}
}
