package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ActionRequiredAnalyticsService;
import com.bigproject.backend.domain.analytics.presentation.dto.ActionRequiredResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping(value = "/classes", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
@RequiredArgsConstructor
public class ClassAnalyticsController {

    private final ActionRequiredAnalyticsService actionRequiredAnalyticsService;

    // -------------------------------------------------------------------------
    // MG-07 담당 반 합계 판정용: 조치 필요 항목 조회
    // -------------------------------------------------------------------------
    @Operation(
            operationId = "findActionRequiredProjects",
            summary = "조치 필요 항목 조회 | ✅ 사용 가능",
            description = """
					MG-07 프로젝트 목록 화면에서 매니저가 담당하는 반 하나의 조치 필요 경보를 조회한다.
					**기수 전체를 보는 `/analytics/actions`와 응답 모양은 같지만 스코프가 다르다** —
					이쪽은 `classId` 하나로 좁힌 반 단위 집계다.

					## 요청 (경로 파라미터)

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `classId` | **필수** | UUID | 조회할 반 |

					쿼리 파라미터는 없다.

					## 응답 (200)

					| 필드 | 타입 | 설명 |
					|---|---|---|
					| `cohortId` | UUID | 이 반이 속한 기수. 조회한 `classId`에서 서버가 역산한다 |
					| `actionCount` | int | `null`이 아닌 경보 수(0~4) |
					| `managerUnassigned` | object? | 담당 매니저 미배정. 없으면 `null` |
					| `conceptGap` | object? | 검증 개념 공백. 없으면 `null` |
					| `groupGap` | object? | 집단 미달. 없으면 `null` |
					| `interviewBacklog` | object? | 면담 적체. 없으면 `null` |

					각 경보 객체의 필드 구조는 `GET /cohorts/{cohortId}/analytics/actions`(조치 필요
					경보 조회)와 완전히 같다 — 같은 서비스(`ActionRequiredAnalyticsService`)와 같은
					DTO(`ActionRequiredResponse`)를 재사용하며 조회 스코프만 기수 대신 반이다.

					## 오류

					| 상태 | 언제 |
					|---|---|
					| 404 | `CLASSROOM_NOT_FOUND` — 그 `classId`가 없거나, 삭제됐거나, **다른 기관 소속**일 때 |

					⚠️ **기관 소속 검증을 여기서 한다.** 로그인한 매니저의 기관과 `classId`가 속한
					기관이 다르면 조회 자체를 막고 404로 답한다 — 다른 기관의 반 데이터가 새어나가지
					않도록 하는 안전장치다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조치 필요 항목 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED 오퍼레이터·매니저가 아님"),
            @ApiResponse(responseCode = "404", description = "CLASSROOM_NOT_FOUND 반을 찾을 수 없음(다른 기관 소속·삭제된 반 포함)"),
    })
    @GetMapping("/{classId}/projects")
    public ResponseEntity<ActionRequiredResponse> getActionRequiredProjects(
            @Parameter(description = "조회할 반 ID") @PathVariable UUID classId,
            Authentication authentication) {

        ActionRequiredResponse response =
                actionRequiredAnalyticsService.getActionRequiredProjects(classId, authentication.getName());
        return ResponseEntity.ok(response);
    }
}