package com.bigproject.backend.domain.projectexecution.presentation;

import com.bigproject.backend.domain.projectexecution.application.CurrentRoundService;
import com.bigproject.backend.domain.projectexecution.presentation.dto.CurrentRoundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.MediaType;

/**
 * 화면 하나 대 API 하나가 아니라 화면이 필요로 하는 조합을 그대로 내려주는 BFF(Backend for Frontend) 성격
 * 엔드포인트를 모은 컨트롤러다. {@code /bff/} 하위는 특정 도메인 리소스가 아니라 화면 단위로 묶인다.
 */
@Tag(name = "Project Execution", description = "프로젝트 구성·일정·요구사항 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v0")
@RequiredArgsConstructor
public class BffController {

    private final CurrentRoundService currentRoundService;

	@Operation(
			summary = "이번 회차 상태 판정 조회 (지금 할 일 하나) | ✅ 사용 가능",
            description = """
					TR-01(교육생 홈)이 보여줄 상태 하나를 조회한다. 진행 중인 회차가 없으면
					status=NO_ACTIVE_ROUND로 200을 내려준다 — 빈 상태는 에러가 아니다.

					**상태 파생 규칙 안내**: 여러 프로젝트에서 동시에 회차가 열려 있을 때 어느 걸
					보여줄지, SESSION_INCOMPLETE(중단) 처리 방식 등은 아직 프론트와 확정되지 않았다.
					status 종류는 CurrentRoundStatus 스키마 설명을 참고할 것.

					**응답 (200)**
					- status: 지금 상태 하나(SUBMISSION_MISSING · SUBMISSION_DEADLINE_PASSED ·
					  ANALYZING · ANALYSIS_FAILED · ASSESSMENT_AVAILABLE · ASSESSMENT_IN_PROGRESS ·
					  ASSESSMENT_WINDOW_CLOSED · COMPLETED_AWAITING_REPORT · REVIEW_AVAILABLE ·
					  NO_ACTIVE_ROUND)
					- 그 외 필드는 status에 따라 의미 있는 것만 채워지고 나머지는 null
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공(회차 없음도 200)"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "NOT_A_TRAINEE 교육생 계정이 아님"),
    })
	@GetMapping(value = "/bff/me/current-round", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurrentRoundResponse> findCurrentRound() {
        return ResponseEntity.ok(currentRoundService.findCurrentRound());
    }
}
