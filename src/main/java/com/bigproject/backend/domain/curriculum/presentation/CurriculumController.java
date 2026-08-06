package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumVersionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
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
 * 애초에 기관(org) 범위라, 로그인한 운영자의 {@code organizationId}(JWT 기반, CurrentUserResolver)를
 * 그대로 쓴다. Cohort 엔티티가 merge되면 "이 cohortId가 실제로 이 기관 것인지" 검증 한 줄만 추가한다
 * — 그 전까지는 cohortId가 다른 기관 것이어도 걸러내지 못한다는 뜻이니 실사용 전 반드시 보완할 것.
 */
@Tag(name = "Curriculum", description = "교안 조회 API (OP-03/OP-04 교안 연결 후보)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATOR')")
@RestController
@RequestMapping("/cohorts/{cohortId}/curricula")
@RequiredArgsConstructor
public class CurriculumController {

    private final CurriculumService curriculumService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(
            summary = "연결 가능한 교안 목록",
            description = "기관 범위의 활성 교안 버전 목록. OP-03 생성 모달·OP-04 구성 탭의 교안 선택 후보."
    )
    @GetMapping
    public ResponseEntity<List<CurriculumVersionResponse>> findLinkableCurricula(
            @PathVariable UUID cohortId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<CurriculumVersionResponse> response = curriculumService.findLinkableCurricula(orgId).stream()
                .map(CurriculumVersionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}