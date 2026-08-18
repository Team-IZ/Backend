package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.application.AssessmentActivityEventService;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityEventsResponse;
import com.bigproject.backend.global.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Assessment", description = "교육생 이해도 확인 회차")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/assessment-attempts", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class AssessmentActivityEventController {

	private final AssessmentActivityEventService service;

	@Operation(
			operationId = "findAssessmentAttemptActivityEvents",
			summary = "이벤트 로그 조회 (매니저) | ✅ 사용 가능",
			description = """
					이 시도(attempt)의 세션에서 벌어진 창 이탈·연결 끊김·첫 타이핑 지연을 **문제(질문)별로**
					집계해 돌려준다.

					## 응답 (200)

					문제마다 발생한 이벤트 타입별로 횟수·지속 시간 합계·발생 건 원본 목록을 담는다.
					한 건도 없던 이벤트 타입은 그 문제의 `events`에 나오지 않는다. 이벤트가 아예 없던
					문제는 `events: []`로 나온다.

					🔴 **응답은 호출자가 담당하는 범위로 제한된다.** 담당하지 않는 시도를 지정하면
					`404 ASSESSMENT_ATTEMPT_NOT_FOUND`다 — 존재하지 않는 것과 남의 담당인 것을 구분하지
					않는다(담당 밖 시도의 존재를 알려줄 이유가 없다).
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "이벤트 로그 조회 성공"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "ASSESSMENT_ATTEMPT_NOT_FOUND",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
	})
	@GetMapping("/{attemptId}/activity-events")
	public ResponseEntity<List<ProblemActivityEventsResponse>> find(
			@PathVariable UUID attemptId, Authentication authentication) {
		return ResponseEntity.ok(service.find(authentication.getName(), attemptId));
	}
}
