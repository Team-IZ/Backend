package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumCatalogItemResponse;
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
            operationId = "findCurriculum",
            summary = "교안 단건 상세 | ✅ 사용 가능",
            description = """
					교안 하나의 머리글을 조회한다(9차 R8).

					`sections`·`projects`는 있는데 **그 위에 놓일 머리글이 어디에서도 오지 않아** 교안 하나를 여는
					화면을 만들 수 없었다. 목록에서 넘어온 값을 들고 가는 수밖에 없었고, 주소로 바로 들어오면
					화면이 비어 있었다.

					**요청**
					- materialId (경로): 교안 ID(버전이 바뀌어도 유지되는 고정 식별자)

					**응답 (200)**

					`GET /organizations/{organizationId}/curricula`의 `content[]` 항목과 **완전히 같은 스키마**다 —
					목록에서 상세로 넘어갈 때 화면이 다루는 모양이 바뀌지 않는다.

					| 필드 | 설명 |
					|---|---|
					| `materialId` · `versionId` | 교안 ID / 최신 버전 ID |
					| `title` · `originalFileName` · `versionNo` · `pageCount` | 머리글 |
					| `analysisStatus` | 가장 최근 분석 **시도** 상태. 한 번도 분석하지 않았으면 null |
					| `sectionCount` · `conceptCount` | `12섹션 · 개념 48건` |
					| `usedProjectCount` | `3개 회차에서 사용 중` |
					| `uploadedAt` · `uploadedByName` | 업로드 시각 / 올린 사람 |

					**섹션 내용은 `GET /curricula/{materialId}/sections`, 쓰는 회차 목록은
					`GET /curricula/{materialId}/projects`가 따로 준다.** 이 API는 머리글만 담당한다 —
					섹션은 교안 하나에 수십 건이라 머리글만 필요한 화면이 그걸 다 받을 이유가 없다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "교안 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 교안을 찾을 수 없음(다른 기관의 교안도 여기로 온다)"),
    })
    @GetMapping
    public ResponseEntity<CurriculumCatalogItemResponse> findCurriculum(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(
                CurriculumCatalogItemResponse.from(curriculumService.findCatalogItem(materialId, orgId)));
    }

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

					## 🔴 분석 전 교안은 409다 (18차 R1)

					이 교안의 최신 버전에 "성공한 분석"이 한 번도 없으면 **`409 CURRICULUM_ANALYSIS_NOT_COMPLETED`**
					를 즉시 반환한다. 화면은 이때 `분석이 끝나면 고를 수 있습니다`를 그리면 된다.

					**종전에는 503이었다.** 그런데 503은 "서버가 지금 요청을 처리할 수 없다"는 인프라
					신호라, 프론트 전역 재시도(`status >= 500`)가 자동으로 3회 붙고 그 재시도가 동시에
					나가면서 프록시 결함(15차 R3)을 밟아 `net::ERR_FAILED`가 됐다 — 화면에는
					**응답이 아예 오지 않는 것처럼** 보였다. 실제로는 요청이 지금 상태와 맞지 않는
					것이지 서버가 아픈 것이 아니므로 409가 정확하다.

					재시도해야 한다는 사실은 상태 코드가 아니라 `code`로 전달한다.

					⚠ 교안 자체가 없으면 그건 영구 실패라 여전히 **404**(`CURRICULUM_MATERIAL_NOT_FOUND`)다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "섹션 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 그 기관에 그 교안이 없음(영구적)"),
            @ApiResponse(responseCode = "409", description = "CURRICULUM_ANALYSIS_NOT_COMPLETED 최신 버전에 성공한 분석이 아직 없음(일시적 — 화면은 `분석이 끝나면 고를 수 있습니다`를 안내한다). **18차 R1로 503에서 내렸다** — 503은 인프라 신호라 프론트 전역 재시도와 프록시가 그대로 밟아 응답이 화면에 닿지 못했다"),
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