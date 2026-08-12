package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.application.AssessmentValidityService;
import com.bigproject.backend.domain.assessment.presentation.dto.AssessmentValidityResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.UpdateAssessmentValidityRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Assessment", description = "교육생 이해도 확인 회차")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/assessment-attempts", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class AssessmentValidityController {
	private final AssessmentValidityService service;

	@Operation(operationId = "updateAssessmentAttemptValidity", summary = "무효 응시 확정·복원 | ⚠️ 사용 불가")
	@PatchMapping(value = "/{attemptId}/validity", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<AssessmentValidityResponse> update(
			@PathVariable UUID attemptId,
			@RequestHeader(value = "X-Request-Id", required = false) String requestId,
			@Valid @RequestBody UpdateAssessmentValidityRequest request,
			Authentication authentication) {
		return ResponseEntity.ok(service.update(authentication.getName(), attemptId, requestId, request));
	}
}
