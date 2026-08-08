package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.presentation.dto.SectionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Curriculum", description = "교안 조회 API (MG-09/OP-06)")
@SecurityRequirement(name = "bearerAuth")
@RestController
// produces를 걸지 않으면 스펙의 content-type이 `*/*`로 나가 생성기가 응답 타입을 좁히지
// 못한다(OpenApiDocumentTest가 잡는다).
@RequestMapping(value = "/curricula/{materialId}", produces = MediaType.APPLICATION_JSON_VALUE)
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

					**요청**
					- materialId (경로): 교안 ID(버전이 바뀌어도 유지되는 고정 식별자)

					**응답 (200)**
					- sections[].sectionId / title / pageStart / pageEnd: 섹션 기본 정보
					- sections[].items[]: 섹션 안 항목(가르친 것) 목록
					  - mappingId / extractedName: 항목 ID / 이름
					  - description: 정의문. definitionMissing=true면 null
					  - definitionMissing: 정의문 미추출 여부
					  - usedAsVerificationConcept: ★ 표시(검증개념으로 확정된 적 있는지)
					  - usedRoundLabels: 검증개념으로 쓰인 회차 라벨 목록

					⚠ 이 교안의 최신 버전에 "성공한 분석"이 한 번도 없으면 404를 반환한다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "섹션 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 그 기관에 그 교안이 없음(영구적)"),
            @ApiResponse(responseCode = "503", description = "CURRICULUM_ANALYSIS_NOT_COMPLETED 최신 버전에 성공한 분석이 아직 없음(일시적 — 화면은 재시도를 안내한다)"),
    })
    @GetMapping("/sections")
    public ResponseEntity<List<SectionResponse>> findSections(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();

        UUID latestVersionId = curriculumVersionRepository
                .findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId)
                .stream().findFirst()
                .map(CurriculumVersion::getVersionId)
                .orElseThrow(() -> new CurriculumException(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

        List<SectionResponse> response = curriculumService.findSections(latestVersionId, orgId).stream()
                .map(SectionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}