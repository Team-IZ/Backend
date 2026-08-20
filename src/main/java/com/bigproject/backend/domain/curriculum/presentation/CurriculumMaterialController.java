package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumCatalogItemResponse;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumVersionResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
					- versionId (쿼리, 선택): 특정 옛 버전의 머리글을 보고 싶을 때 그 버전 ID를 넘긴다.
					  생략하면 최신 버전으로 해석한다(`sections`와 같은 규칙). 그 교안(materialId)의
					  버전이 아니거나 존재하지 않으면 404 `CURRICULUM_VERSION_NOT_FOUND`다
					  (2026-08-20, 44차 R1).

					**응답 (200)**

					`GET /organizations/{organizationId}/curricula`의 `content[]` 항목과 **완전히 같은 스키마**다 —
					목록에서 상세로 넘어갈 때 화면이 다루는 모양이 바뀌지 않는다.

					| 필드 | 설명 |
					|---|---|
					| `materialId` · `versionId` | 교안 ID / **기준 버전** ID(위 `versionId`를 생략하면 최신) |
					| `title` · `originalFileName` · `versionNo` · `pageCount` | 기준 버전의 머리글 |
					| `analysisStatus` | 기준 버전의 가장 최근 분석 **시도** 상태. 한 번도 분석하지 않았으면 null |
					| `sectionCount` · `conceptCount` | `12섹션 · 개념 48건` — 기준 버전 기준 |
					| `usedProjectCount` | `3개 회차에서 사용 중` — **교안 전체(모든 버전) 기준으로 고정**이다. 기준 버전을
					  바꿔도 이 값은 바뀌지 않는다(삭제 가드와 같은 모집단을 유지해야 하기 때문) |
					| `versionUsedProjectCount` | 기준 버전 **하나만** 쓴 회차 수 (2026-08-20, 44차 R1) |
					| `uploadedAt` · `uploadedByName` | 기준 버전 업로드 시각 / 올린 사람 |

					**섹션 내용은 `GET /curricula/{materialId}/sections?versionId=`, 쓰는 회차 목록은
					`GET /curricula/{materialId}/projects?versionId=`가 같은 `versionId`로 따로 준다.**
					이 API는 머리글만 담당한다 — 섹션은 교안 하나에 수십 건이라 머리글만 필요한 화면이
					그걸 다 받을 이유가 없다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "교안 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 교안을 찾을 수 없음(다른 기관의 교안도 여기로 온다) 또는 CURRICULUM_VERSION_NOT_FOUND versionId를 넘겼는데 그 버전이 없거나 이 교안의 버전이 아님(44차 R1)"),
    })
    @GetMapping
    public ResponseEntity<CurriculumCatalogItemResponse> findCurriculum(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId,
            @Parameter(description = "특정 옛 버전의 머리글을 보고 싶을 때 그 버전 ID. 생략하면 최신 버전으로 해석한다.")
            @RequestParam(required = false) UUID versionId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(CurriculumCatalogItemResponse.from(
                curriculumService.findCatalogItem(materialId, versionId, orgId)));
    }

    @Operation(
            operationId = "deleteCurriculum",
            summary = "교안 삭제 | ✅ 사용 가능",
            description = """
					교안 하나를 목록에서 지운다(25차 R11).

					종전에는 삭제 경로가 아예 없어 **한 번 올린 교안이 기관 목록에서 영원히 사라지지
					않았다.** 잘못 올린 파일·시험 삼아 올린 파일이 그대로 쌓이고, 회차를 만들 때
					그 목록에서 골라야 했다.

					**요청**
					- materialId (경로): 교안 ID

					**응답 (204)**
					- 본문 없음

					## 연결된 회차가 있으면 409다

					이 교안을 쓰는 회차가 하나라도 있으면 지우지 않고 `409 CURRICULUM_MATERIAL_IN_USE`다 —
					회차의 문항이 근거로 삼는 교안이 목록에서 사라지면 안 되기 때문이다. 판정 기준은
					목록·상세의 `usedProjectCount`와 **같은 식**이라, 화면이 `0개 회차에서 사용 중`으로
					읽은 교안이 삭제에서만 막히는 일은 없다.

					먼저 연결을 끊으려면 `DELETE /projects/{projectId}/curricula/{curriculumVersionId}`를
					쓴다. 어느 회차가 쓰는지는 `GET /curricula/{materialId}/projects`가 준다.

					## 행은 남는다 — 지우는 것은 목록에서다

					`deleted_at`만 찍는 논리 삭제다. 분석 이력·섹션·개념 매핑이 이 교안을 참조하고 있어
					물리 삭제는 그 이력까지 함께 지운다.

					**제목은 더 이상 점유되지 않는다.** 삭제와 동시에 내부적으로 제목을 봉인해 두므로,
					지운 교안과 같은 제목으로 바로 다시 등록할 수 있다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "교안 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 교안을 찾을 수 없음(이미 지운 교안·다른 기관의 교안도 여기로 온다)"),
            @ApiResponse(responseCode = "409", description = "CURRICULUM_MATERIAL_IN_USE 이 교안을 쓰는 회차가 있음 — 연결을 먼저 끊어야 한다"),
    })
    @DeleteMapping
    public ResponseEntity<Void> deleteCurriculum(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        curriculumService.deleteCurriculum(materialId, orgId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "교안 섹션·개념 조회 | ✅ 사용 가능",
            description = """
					materialId(교안 자체의 고정 식별자)를 받아 그 교안의 최신 버전으로 해석한 뒤,
					그 버전의 최근 성공한 분석이 만든 섹션 전체를 하위 항목(★ 검증 개념 표시 포함)까지
					트리로 내려준다.

					**요청**
					- materialId (경로): 교안 ID(버전이 바뀌어도 유지되는 고정 식별자)
					- versionId (쿼리, 선택): 특정 옛 버전의 섹션을 보고 싶을 때 그 버전 ID를 넘긴다.
					  생략하면 종전과 동일하게 최신 버전으로 해석한다. 그 교안(materialId)의 버전이
					  아니거나 존재하지 않으면 404 `CURRICULUM_VERSION_NOT_FOUND`다.

					**응답 (200)**
					- sections[].sectionId / title / pageStart / pageEnd: 섹션 기본 정보
					- sections[].items[]: 섹션 안 항목(가르친 것) 목록
					  - mappingId / extractedName: 항목 ID / 이름
					  - description: 정의문. definitionMissing=true면 null
					  - definitionMissing: 정의문 미추출 여부
					  - usedAsVerificationConcept: ★ 표시(검증개념으로 확정된 적 있는지)
					  - usedRoundLabels: 검증개념으로 쓰인 회차 라벨 목록

					## 🔴 분석 전 교안은 409다 (18차 R1)

					이 교안의 최신 버전에 "성공한 분석"이 한 번도 없으면 409를 즉시 반환한다 — 그런데
					`code`가 두 가지로 갈린다.

					- **`CURRICULUM_ANALYSIS_NOT_COMPLETED`**: 아직 분석을 안 걸었거나 PENDING·RUNNING 중.
					  **일시적**이다 — 화면은 `분석이 끝나면 고를 수 있습니다`를 그리면 된다.
					- **`CURRICULUM_ANALYSIS_FAILED`**: 가장 최근 분석 시도가 실패로 끝남. **영구적**이다 —
					  기다려도 저절로 풀리지 않는다. 화면은 `분석에 실패했습니다. 재분석을 요청해 주세요`를
					  그리고 재분석 버튼을 보여줘야 한다.

					**종전에는 503이었다.** 그런데 503은 "서버가 지금 요청을 처리할 수 없다"는 인프라
					신호라, 프론트 전역 재시도(`status >= 500`)가 자동으로 3회 붙고 그 재시도가 동시에
					나가면서 프록시 결함(15차 R3)을 밟아 `net::ERR_FAILED`가 됐다 — 화면에는
					**응답이 아예 오지 않는 것처럼** 보였다. 실제로는 요청이 지금 상태와 맞지 않는
					것이지 서버가 아픈 것이 아니므로 409가 정확하다.

					재시도해야 하는지·재분석을 걸어야 하는지는 상태 코드가 아니라 `code`로 전달한다.

					⚠ 교안 자체가 없으면 그건 영구 실패라 여전히 **404**(`CURRICULUM_MATERIAL_NOT_FOUND`)다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "섹션 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 그 기관에 그 교안이 없음(영구적) 또는 CURRICULUM_VERSION_NOT_FOUND versionId를 넘겼는데 그 버전이 없거나 이 교안의 버전이 아님"),
            @ApiResponse(responseCode = "409", description = "CURRICULUM_ANALYSIS_NOT_COMPLETED 대상 버전에 성공한 분석이 아직 없음(일시적 — 화면은 `분석이 끝나면 고를 수 있습니다`를 안내한다) 또는 CURRICULUM_ANALYSIS_FAILED 가장 최근 분석이 실패로 끝남(영구적 — 화면은 재분석을 안내한다). **18차 R1로 503에서 내렸다** — 503은 인프라 신호라 프론트 전역 재시도와 프록시가 그대로 밟아 응답이 화면에 닿지 못했다"),
    })
    @GetMapping("/sections")
    public ResponseEntity<List<SectionResponse>> findSections(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId,
            @Parameter(description = "특정 옛 버전의 섹션을 보고 싶을 때 그 버전 ID. 생략하면 최신 버전으로 해석한다.")
            @RequestParam(required = false) UUID versionId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();

        UUID targetVersionId = curriculumService.resolveVersionId(materialId, versionId, orgId);

        List<SectionResponse> response = curriculumService.findSections(targetVersionId, orgId).stream()
                .map(SectionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "findCurriculumVersionHistory",
            summary = "교안 버전 이력 | ✅ 사용 가능",
            description = """
					이 교안(material)의 전체 버전을 최신순으로 돌려준다. 상세 화면에서 "예전 버전"
					목록을 보여줄 때 쓴다.

					**요청**
					- materialId (경로): 교안 ID(버전이 바뀌어도 유지되는 고정 식별자)

					**응답 (200)**

					`versionNo` 내림차순 배열. 새 버전을 올려도 기존 버전 행이 지워지거나 바뀌지 않고
					그대로 남아 있고, 상태도 계속 `ACTIVE`로 유지되므로 과거 버전도 전부 포함된다.
					각 항목의 스키마는 `CurriculumVersionResponse`(연결 가능한 교안 목록과 동일)다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "버전 이력 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 그 기관에 그 교안이 없음"),
    })
    @GetMapping("/versions")
    public ResponseEntity<List<CurriculumVersionResponse>> findCurriculumVersionHistory(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<CurriculumVersionResponse> response = curriculumService.findVersionHistory(materialId, orgId).stream()
                .map(CurriculumVersionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}
