package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumVersionResponse;
import com.bigproject.backend.domain.curriculum.presentation.dto.SectionItemResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * ⚠ 임시: {@code cohortId}를 경로에 받지만 지금은 검증에 쓰지 않는다. curriculum_material/version이
 * 애초에 기관(org) 범위라, 로그인한 운영자의 {@code organizationId}(JWT 기반)를 그대로 쓴다.
 * Cohort 엔티티가 merge되면 "이 cohortId가 실제로 이 기관 것인지" 검증 한 줄만 추가한다.
 */
@Tag(name = "Curriculum", description = "교안 조회 API (MG-09/OP-06)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/cohorts/{cohortId}/curricula")
@RequiredArgsConstructor
public class CurriculumController {

    private final CurriculumService curriculumService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(summary = "교안 목록", description = "기관 범위의 활성 교안 버전 목록.")
    @GetMapping
    public ResponseEntity<List<CurriculumVersionResponse>> findLinkableCurricula(
            @Parameter(description = "경로상 기수 ID(현재 미검증)") @PathVariable UUID cohortId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<CurriculumVersionResponse> response = curriculumService.findLinkableCurricula(orgId).stream()
                .map(CurriculumVersionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "섹션 상세",
            description = "섹션 안의 항목들을 문항 순서대로. ★가 검증 개념으로 쓰인 항목이며, " +
                    "쓰인 회차(usedRoundLabels)를 함께 내려준다."
    )
    @GetMapping("/{versionId}/sections/{sectionId}")
    public ResponseEntity<List<SectionItemResponse>> findSectionItems(
            @Parameter(description = "경로상 기수 ID(현재 미검증)") @PathVariable UUID cohortId,
            @Parameter(description = "교안 버전 ID(현재 조회에는 미사용 — sectionId로 충분)") @PathVariable UUID versionId,
            @Parameter(description = "섹션 ID") @PathVariable UUID sectionId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<SectionItemResponse> response = curriculumService.findSectionItems(sectionId, orgId).stream()
                .map(SectionItemResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}