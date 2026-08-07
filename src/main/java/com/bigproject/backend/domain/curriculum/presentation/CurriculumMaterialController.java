package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.presentation.dto.SectionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Tag(name = "Curriculum", description = "교안 조회 API (MG-09/OP-06)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/curricula/{materialId}")
@RequiredArgsConstructor
public class CurriculumMaterialController {

    private final CurriculumService curriculumService;
    private final CurriculumVersionRepository curriculumVersionRepository;
    private final CurrentUserResolver currentUserResolver;

    @Operation(
            summary = "교안 섹션·개념 조회 | ✅ 사용 가능",
            description = """
                    materialId(교안 자체의 고정 식별자)를 받아 그 교안의 최신 버전으로 해석한 뒤,
                    그 버전의 최근 성공한 분석이 만든 섹션 전체를 하위 항목(★ 검증 개념 표시 포함)까지
                    트리로 내려준다.
                    """
    )
    @GetMapping("/sections")
    public ResponseEntity<List<SectionResponse>> findSections(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();

        UUID latestVersionId = curriculumVersionRepository
                .findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId)
                .stream().findFirst()
                .map(CurriculumVersion::getVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "교안을 찾을 수 없습니다."));

        List<SectionResponse> response = curriculumService.findSections(latestVersionId, orgId).stream()
                .map(SectionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}