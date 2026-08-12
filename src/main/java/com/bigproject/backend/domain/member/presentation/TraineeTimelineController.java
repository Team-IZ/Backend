package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.TraineeTimelineService;
import com.bigproject.backend.domain.member.presentation.dto.TraineeTimelineResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Member")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/trainees", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class TraineeTimelineController {
	private final TraineeTimelineService service;

	@Operation(operationId = "findManagerTraineeTimeline", summary = "교육생 통합 타임라인 조회 | ✅ 사용 가능")
	@GetMapping("/{traineeId}/timeline")
	public ResponseEntity<TraineeTimelineResponse> findTimeline(
			@PathVariable UUID cohortId, @PathVariable UUID traineeId,
			@RequestParam(required = false) TraineeTimelineResponse.Type type,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Authentication authentication) {
		return ResponseEntity.ok(service.findTimeline(authentication.getName(), cohortId, traineeId, type, cursor, size));
	}
}
