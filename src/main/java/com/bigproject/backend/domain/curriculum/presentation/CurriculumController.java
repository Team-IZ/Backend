package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogSort;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumCatalogItemResponse;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumCatalogResponse;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumVersionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Curriculum", description = "교안 조회 API (MG-09/OP-06)")
@SecurityRequirement(name = "bearerAuth")
@Validated
@RestController
// 응답은 전부 JSON이다. 안 걸면 스펙의 content-type이 `*/*`로 나가 생성기가 응답 타입을
// 좁히지 못한다(OpenApiDocumentTest가 잡는다). 요청이 multipart인 registerCurriculum도
// consumes와 produces는 별개라 영향이 없다.
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CurriculumController {

    private final CurriculumService curriculumService;
    private final CurrentUserResolver currentUserResolver;

    @Operation(
            operationId = "findOrganizationCurricula",
            summary = "기관 교안 목록 | ✅ 사용 가능",
            description = """
					OP-06 `교안` 탭의 표를 채운다. **기관 전체 범위**이며 기수 스위처의 영향을 받지 않는다(9차 R8).

					지금까지는 기수마다 `GET /cohorts/{cohortId}/curricula`를 부르고 합쳐야 했는데,
					① 기수가 늘면 요청이 그만큼 늘고 ② *"연결 가능한 것"*은 전체와 같지 않다 —
					이미 연결됐거나 분석에 실패한 교안이 빠진다. 이 API는 **기관의 모든 교안**을 준다.

					## 요청

					| 파라미터 | 필수 | 타입 | 설명 |
					|---|---|---|---|
					| `organizationId` | **필수**(경로) | UUID | 기관 식별자. 호출자의 소속 기관과 다르면 403 |
					| `query` | 선택 | string | 파일명·교안 제목 부분검색(대소문자 무시) |
					| `status` | 선택 | enum | `PENDING` · `RUNNING` · `SUCCEEDED` · `FAILED`. 최신 버전의 **가장 최근 분석 시도** 기준 |
					| `sort` | 선택 | enum | `RECENT`(최근 업로드 순, **기본**) · `NAME`(파일명순) · `USAGE`(사용 회차 많은 순) |
					| `page` | 선택 | int | 0부터 시작. 기본 `0` |
					| `size` | 선택 | int | 페이지당 개수. 기본 `20`, 최대 `100` |

					## 응답 (200)

					`content[]` · `page` · `size` · `totalElements` · `totalPages` —
					매니저 목록과 같은 모양이다. **`totalElements`가 OP-06 교안 탭의 배지 숫자**다.

					### content[] 각 항목 — 한 행이 **교안 하나**, 값은 **최신 버전** 기준

					| 필드 | 화면 |
					|---|---|
					| `materialId` · `versionId` | 상세 이동 / 프로젝트 연결에 보낼 값 |
					| `title` · `originalFileName` · `versionNo` | 이름 열 |
					| `analysisStatus` | `분석 중`(PENDING·RUNNING) · `분석 완료`(SUCCEEDED) · `분석 실패`(FAILED) 배지 |
					| `sectionCount` · `conceptCount` | `12섹션 · 개념 48건` |
					| `usedProjectCount` | `3개 회차에서 사용 중` — 삭제·교체 판단 근거 |
					| `uploadedAt` · `uploadedByName` | 비고 |

					⚠️ **`analysisStatus`는 한 번도 분석하지 않은 교안에서 `null`이다.** 실패와 구분해야 해서
					값을 만들어 넣지 않는다. `POST /curricula/{materialId}/analyses`로 재분석을 건 뒤
					이 값을 폴링하면 진행 중인지 실패했는지 알 수 있다.

					💡 **한 행이 교안(material) 하나다.** 버전은 같은 자리의 새 파일이지 별도 항목이 아니라서,
					값은 전부 최신 버전 기준이고 `usedProjectCount`만 모든 버전을 합쳐 센다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "기관 교안 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED page·size 값이 올바르지 않음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ORG_ACCESS_DENIED 다른 기관의 교안은 조회할 수 없음"),
    })
    @GetMapping("/organizations/{organizationId}/curricula")
    public ResponseEntity<CurriculumCatalogResponse> findOrganizationCurricula(
            @Parameter(description = "기관 ID. 호출자의 소속 기관만 허용된다", example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable UUID organizationId,
            @Parameter(description = "파일명·교안 제목 부분검색", example = "spring")
            @RequestParam(required = false) String query,
            @Parameter(description = "분석 상태 필터. 최신 버전의 가장 최근 분석 시도 기준")
            @RequestParam(required = false) CurriculumAnalysisStatus status,
            @Parameter(description = "정렬 기준", example = "RECENT")
            @RequestParam(required = false, defaultValue = "RECENT") CurriculumCatalogSort sort,
            @Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지당 개수(최대 100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        assertOwnOrganization(organizationId, orgId);

        CurriculumService.CurriculumCatalogPage catalogPage =
                curriculumService.findCatalog(orgId, query, status, sort, page, size);

        return ResponseEntity.ok(new CurriculumCatalogResponse(
                catalogPage.content().stream().map(CurriculumCatalogItemResponse::from).toList(),
                catalogPage.page(),
                catalogPage.size(),
                catalogPage.totalElements(),
                catalogPage.totalPages()));
    }

    /** 경로의 기관과 토큰의 기관이 같은지 본다. 다른 기관 ID로 남의 교안을 읽는 경로를 막는다. */
    private void assertOwnOrganization(UUID pathOrganizationId, UUID callerOrganizationId) {
        if (!callerOrganizationId.equals(pathOrganizationId)) {
            throw new com.bigproject.backend.domain.organization.domain.OrganizationException(
                    com.bigproject.backend.domain.organization.domain.OrganizationErrorCode.ORG_ACCESS_DENIED,
                    "다른 기관의 교안은 조회할 수 없습니다.");
        }
    }

    @Operation(
            summary = "기수 연결 교안 목록 | ✅ 사용 가능",
            description = """
					기관 범위의 활성 교안 버전 목록을 조회한다.

					**요청**
					- cohortId (경로): 현재 미검증(항상 토큰의 기관 범위로 조회)

					**응답 (200)**
					- versionId / materialId: 교안 버전 ID / 원장 ID
					- versionNo: 버전 번호
					- originalFileName / pageCount: 파일명 / 페이지 수
					- createdAt: 등록 시각
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "교안 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
    })
    @GetMapping("/cohorts/{cohortId}/curricula")
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
            summary = "쓰인 회차 | ✅ 사용 가능",
            description = """
					이 교안 버전을 연결한 프로젝트(회차)들의 "미프 N차" 라벨 목록을 조회한다.

					**요청**
					- materialId (경로): 교안 버전 ID

					**응답 (200)**
					- 문자열 배열. 연결된 프로젝트가 없으면 빈 배열
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "쓰인 회차 조회 성공"),
            @ApiResponse(responseCode = "400", description = "BIG_PROJECT_HAS_NO_ROUND_LABEL 연결된 프로젝트가 빅프로젝트라 회차 라벨이 없음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "PROJECT_NOT_FOUND 연결된 프로젝트를 찾을 수 없음"),
    })
    @GetMapping("/curricula/{materialId}/projects")
    public ResponseEntity<List<String>> findUsedProjects(
            @Parameter(description = "교안 버전 ID") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(curriculumService.findUsedProjects(materialId, orgId));
    }

    @Operation(
            summary = "비교 가능한 기수 목록 | ✅ 사용 가능",
            description = """
					이 기수가 쓴 교안과 하나라도 겹치는 교안을 쓴 다른 기수 ID 목록을 조회한다.

					**요청**
					- cohortId (쿼리, 필수): 기준 기수 ID

					**응답 (200)**
					- UUID 배열. 겹치는 교안이 없으면 빈 배열
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비교 가능한 기수 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
    })
    @GetMapping("/curricula/comparable-cohorts")
    public ResponseEntity<List<UUID>> findComparableCohorts(
            @Parameter(description = "기준 기수 ID") @RequestParam UUID cohortId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(curriculumService.findComparableCohorts(cohortId, orgId));
    }

    @Operation(
            summary = "교안 등록 | ✅ 사용 가능",
            description = """
					PDF 파일을 업로드해 새 교안(material)과 첫 버전(version)을 만든다.
					⚠ 파일은 현재 로컬 디스크에 저장된다(uploads/curricula/) — 나중에 S3 등으로 교체 예정.
					⚠ 등록 직후엔 분석이 안 된 상태다. 섹션·검증개념을 쓰려면 별도로
					`POST /curricula/{materialId}/analyses`를 호출해야 한다.

					**요청 (multipart/form-data)**
					- file (필수): PDF 파일
					- title (필수): 교안 제목
					- topic (선택): 주제

					**응답 (201)**
					- 생성된 교안 버전 정보(응답 필드는 "기수 연결 교안 목록"과 동일)
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "교안 등록 성공"),
            @ApiResponse(responseCode = "400", description = "CURRICULUM_FILE_REQUIRED 업로드할 파일이 없음 · VALIDATION_FAILED title 등 필수값 누락"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
    })
    @PostMapping(value = "/curricula", consumes = "multipart/form-data")
    public ResponseEntity<CurriculumVersionResponse> registerCurriculum(
            @Parameter(description = "PDF 파일") @RequestParam("file") MultipartFile file,
            @Parameter(description = "교안 제목") @RequestParam("title") String title,
            @Parameter(description = "주제(선택)") @RequestParam(value = "topic", required = false) String topic
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        var version = curriculumService.registerCurriculum(orgId, title, topic, file, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(CurriculumVersionResponse.from(version));
    }

    @Operation(
            summary = "재분석 요청 | ✅ 사용 가능",
            description = """
					교안의 재분석을 AI 서버에 요청한다.

					**요청**
					- materialId (경로): 교안 ID

					**응답 (202)**
					- 본문 없음. 요청이 접수됐다는 뜻이며, 분석 완료 여부는
					  `GET /curricula/{materialId}/sections`로 나중에 확인한다(503이면 아직 미완료)
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "재분석 요청 접수됨"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 교안 원장이 없거나 그 교안에 버전이 하나도 없음"),
    })
    @PostMapping("/curricula/{materialId}/analyses")
    public ResponseEntity<Void> requestAnalysis(
            @Parameter(description = "교안 ID") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
        curriculumService.requestAnalysis(materialId, orgId, actorUserId);
        return ResponseEntity.accepted().build();
    }
}