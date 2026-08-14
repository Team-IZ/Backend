package com.bigproject.backend.domain.analytics.presentation;

import com.bigproject.backend.domain.analytics.application.ActionRequiredAnalyticsService;
import com.bigproject.backend.domain.analytics.presentation.dto.ActionRequiredResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Class Analytics", description = "반 기준 분석 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/api/v0/classes", produces = MediaType.APPLICATION_JSON_VALUE)
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
            description = "MG-07 프로젝트 목록 화면에서 특정 반의 조치 필요 항목을 조회합니다."
    )
    @GetMapping("/{classId}/projects")
    public ResponseEntity<List<ActionRequiredResponse>> getActionRequiredProjects(
            @PathVariable UUID classId) {

        List<ActionRequiredResponse> response = actionRequiredAnalyticsService.getActionRequiredProjects(classId);
        return ResponseEntity.ok(response);
    }
}