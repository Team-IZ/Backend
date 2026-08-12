package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ManagerViewAnalyticsService;
import com.bigproject.backend.domain.analytics.presentation.dto.ConceptScopeResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.ManagerHeatmapResponse;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskSignalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Analytics", description = "기수 분석 격자 조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/cohorts/{cohortId}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@RequiredArgsConstructor
public class ManagerViewAnalyticsController {
	private final ManagerViewAnalyticsService service;

	@Operation(
			operationId = "findManagerHeatmap",
			summary = "매니저 히트맵 조회 | ✅ 사용 가능",
			description = """
					회차 × 검증 개념 격자를 반·팀·팀원 세 계층으로 조회한다.
					**계층이 달라도 응답 모양이 같다** — `rows[]`가 언제나 그 계층의 비교 단위이고
					고정된 상위 계층은 `scope`가 한 번만 싣는다.

					## 요청 (쿼리 파라미터)

					| 파라미터 | 필수 | 설명 |
					|---|---|---|
					| `projectId` | 필수 | 조회할 프로젝트 |
					| `assessmentRoundId` | 필수 | 조회할 회차. 열(검증 개념)이 회차마다 다르다 |
					| `level` | 필수 | `CLASS` · `TEAM` · `TRAINEE` |
					| `attemptView` | 선택 | `INITIAL`(기본) · `REVIEW`. `REVIEW`는 `TRAINEE`만 허용 |
					| `classroomId` | 조건부 | `TEAM`·`TRAINEE`에서 **필수**, `CLASS`에서는 지정하면 400 |
					| `teamId` | 조건부 | `TRAINEE`에서 **필수**, 그 외에는 지정하면 400 |

					## 응답 (200)

					| 필드 | 설명 |
					|---|---|
					| `asOfAt` | 집계 시각. **응답 전체의 성질**이라 셀마다 싣지 않는다 |
					| `scope` | 고정된 상위 계층. `CLASS`는 고정 상위가 없어 `null` |
					| `concepts[]` | 가로축(열 머리). `problemNo` · `teachesId` · `conceptName` · `groupShortfall` |
					| `summary` | 화면 상단 합계 행. `rowId`·`rowName`은 `null` |
					| `rows[]` | 세로축. `rowId`·`rowName`·`memberCount`·`cells[]` |
					| `navigation` | 툴바 셀렉터용 반·팀 목록. **`CLASS`에서는 비운다**(`rows`와 같은 값) |

					### rows[].cells[]

					| 필드 | 설명 |
					|---|---|
					| `value` | 집계 행은 **평균**, 개인 행은 **도달 단계 원값**(0~4) |
					| `validCount`·`notAttendedCount`·`invalidCount`·`interruptedCount` | 결과 구분별 인원 |
					| `status` | 집계 상태 또는 개인 응시 결과 상태 |
					| `groupShortfall` | 집단 미달. **반 행에만** 채운다 |
					| `initialLevel`·`comparisonLevel`·`delta` | `REVIEW` 전용. `INITIAL`에서는 **키 자체가 빠진다** |

					⚠️ **`memberCount`는 명부 인원이라 `validCount`와 다르다.** 응시하지 않은 사람을 포함한다.

					⚠️ **`summary`는 개인 단위 가중 평균이다.** 반별 평균을 다시 평균 내면 인원이 다른 반이
					같은 무게가 되어 값이 달라지므로 화면에서 `rows`를 평균 내지 않는다.

					💡 **집단 미달은 유효 응시자 기준이다** — 유효 응시자의 절반을 **넘는** 인원이 2단 이하일 때
					참이며 미응시·무효 확정·중단은 분모에서 뺀다. 면담 브리프의 개념 소관 판정과 같은 산식이다.

					💡 **팀 행에는 `groupShortfall`을 달지 않는다.** 팀은 3~4명이라 한 사람이 판정을 뒤집는다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "격자 조회 성공"),
			@ApiResponse(responseCode = "400", description = "HEATMAP_SCOPE_INVALID 계층과 classroomId·teamId 조합이 맞지 않음 · HEATMAP_REVIEW_TRAINEE_REQUIRED REVIEW는 TRAINEE 계층만 허용"),
			@ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 만료됨"),
			@ApiResponse(responseCode = "403", description = "ACCESS_DENIED 매니저 권한이 아님"),
			@ApiResponse(responseCode = "404", description = "MANAGER_SCOPE_NOT_FOUND 담당 범위 밖의 기수이거나 존재하지 않음")
	})
	@GetMapping("/heatmap")
	public ResponseEntity<ManagerHeatmapResponse> findHeatmap(
			@PathVariable UUID cohortId, @RequestParam UUID projectId,
			@RequestParam UUID assessmentRoundId, @RequestParam ManagerHeatmapResponse.Level level,
			@RequestParam(defaultValue = "INITIAL") ManagerHeatmapResponse.AttemptView attemptView,
			@RequestParam(required = false) UUID classroomId, @RequestParam(required = false) UUID teamId,
			Authentication authentication) {
		return ResponseEntity.ok(service.findHeatmap(authentication.getName(), cohortId, projectId,
				assessmentRoundId, level, attemptView, classroomId, teamId));
	}

	@Operation(operationId = "findManagerRiskSignals", summary = "매니저 위험 신호 근거 조회 | ⚠️ 사용 불가")
	@GetMapping("/risk-signals")
	public ResponseEntity<RiskSignalResponse> findRiskSignals(
			@PathVariable UUID cohortId, @RequestParam(required = false) UUID assessmentRoundId,
			@RequestParam(required = false) UUID classroomId, @RequestParam(required = false) UUID traineeId,
			@RequestParam(required = false) String reasonCode, Authentication authentication) {
		return ResponseEntity.ok(service.findRiskSignals(authentication.getName(), cohortId,
				assessmentRoundId, classroomId, traineeId, reasonCode));
	}

	@Operation(operationId = "findManagerConceptScope", summary = "면담 브리프 개념 소관 판정 | ⚠️ 사용 불가")
	@GetMapping("/concept-scope")
	public ResponseEntity<ConceptScopeResponse> findConceptScope(
			@PathVariable UUID cohortId, @RequestParam UUID assessmentRoundId,
			@RequestParam UUID classroomId, @RequestParam UUID teachesId, Authentication authentication) {
		return ResponseEntity.ok(service.findConceptScope(authentication.getName(), cohortId,
				assessmentRoundId, classroomId, teachesId));
	}
}
