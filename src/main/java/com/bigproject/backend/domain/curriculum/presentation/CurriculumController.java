package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogSort;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumCatalogItemResponse;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumCatalogResponse;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumUsingProjectResponse;
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
					| `notAnalyzedOnly` | 선택 | boolean | **한 번도 분석하지 않은 교안만.** 기본 `false`(13차 R2) |
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

					## `분석 전`만 골라 보기 (13차 R2)

					위 이유로 그런 교안은 **`status`로 고를 수 없다** — 상태가 없기 때문이다.
					`notAnalyzedOnly=true`가 그 자리다. 헤더의 `notAnalyzedCount`와 **같은 기준**이라
					그 숫자를 눌러 좁히면 그만큼 나온다.

					`status`를 `NOT_ANALYZED` 같은 값으로 늘리지 않은 이유는 그 enum이 **응답의
					`analysisStatus`와 같은 타입**이기 때문이다. 넣으면 응답이 절대 갖지 않는 값을
					타입이 허용하게 된다. 명단의 `unassignedOnly`와 같은 모양으로 뒀다.

					⚠️ **`status`와 `notAnalyzedOnly=true`를 함께 보내면 400** `CURRICULUM_FILTER_CONFLICT`다 —
					서로를 배제하는 조건이라 결과가 항상 비는데, 빈 목록을 조용히 주면 화면이
					"그런 교안이 없다"로 읽는다.

					💡 **한 행이 교안(material) 하나다.** 버전은 같은 자리의 새 파일이지 별도 항목이 아니라서,
					값은 전부 최신 버전 기준이고 `usedProjectCount`만 모든 버전을 합쳐 센다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "기관 교안 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED page·size 값이 올바르지 않음 · "
                    + "CURRICULUM_FILTER_CONFLICT status와 notAnalyzedOnly를 함께 지정함"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "403", description = "ORG_ACCESS_DENIED 다른 기관의 교안은 조회할 수 없음"),
    })
    @GetMapping("/organizations/{organizationId}/curricula")
    public ResponseEntity<CurriculumCatalogResponse> findOrganizationCurricula(
            @Parameter(description = "기관 ID. 호출자의 소속 기관만 허용된다", example = "123e4567-e89b-12d3-a456-426614174000")
            @PathVariable UUID organizationId,
            @Parameter(description = "파일명·교안 제목 부분검색", example = "spring")
            @RequestParam(required = false) String query,
            @Parameter(description = "분석 상태 필터. 최신 버전의 가장 최근 분석 시도 기준. "
                    + "`notAnalyzedOnly=true`와 함께 보내면 400이다")
            @RequestParam(required = false) CurriculumAnalysisStatus status,
            @Parameter(description = """
                    한 번도 분석하지 않은 교안만 남깁니다(13차 R2).

                    그런 교안은 분석 상태가 **없어서** `status`로는 고를 수 없습니다 —
                    그래서 상태 축이 아니라 별도 조건이며, 명단의 `unassignedOnly`와 같은 모양입니다.
                    응답 헤더의 `notAnalyzedCount`가 세는 것과 **같은 기준**이라 그 숫자를 누르면
                    그만큼 나옵니다.

                    `status`와 함께 보내면 서로를 배제하므로 400 `CURRICULUM_FILTER_CONFLICT`입니다.
                    """, example = "false")
            @RequestParam(required = false, defaultValue = "false") boolean notAnalyzedOnly,
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
                curriculumService.findCatalog(orgId, query, status, notAnalyzedOnly, sort, page, size);

        return ResponseEntity.ok(new CurriculumCatalogResponse(
                catalogPage.content().stream().map(CurriculumCatalogItemResponse::from).toList(),
                catalogPage.page(),
                catalogPage.size(),
                catalogPage.totalElements(),
                catalogPage.totalPages(),
                catalogPage.statusCounts(),
                catalogPage.notAnalyzedCount()));
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
					- originalFileName: 파일명
					- pageCount: 페이지 수. **분석 전이면 null**
					- analysisStatus: 분석 상태. **한 번도 분석하지 않았으면 null**
					- teachesCount: 승인된 가르친 항목 수
					- createdAt: 등록 시각

					## 🔴 분석 여부를 pageCount로 추측하지 말 것 (18차 R2)

					종전에는 상태 필드가 없어 화면이 `pageCount == null`로 분석 여부를 추측하고
					있었다. 그건 **"쪽수를 아직 모른다"는 뜻이지 "분석 중"이 아니고**, 분석이
					**실패**한 교안과도 구분되지 않는다. `analysisStatus`를 쓴다.

					| `analysisStatus` | 화면 |
					|---|---|
					| `null` | `분석 전` · 못 고름 |
					| `PENDING` · `RUNNING` | `분석 중 — 끝나면 고를 수 있습니다` · 못 고름 |
					| `SUCCEEDED` | 고를 수 있음 |
					| `FAILED` | `분석 실패 — 다시 올려 주세요` · 못 고름 |

					⚠️ 값이 **4종 + null**이다(요청서의 `ANALYZING`·`READY`·`FAILED` 3종이 아니다).
					`PENDING`과 `RUNNING`을 화면에서 `분석 중` 하나로 접으시면 되고, **`null`(분석 전)과
					`FAILED`(분석 실패)는 갈라야 한다** — 전자는 기다리면 되고 후자는 다시 올려야 한다.

					`teachesCount`는 고르기 **전에** 이 교안에서 검증 개념 3건을 뽑을 수 있는지
					알려 준다. `GET /projects/{projectId}/concept-candidates`가 세는 것과 같은 값이다.

					**조회는 교안 수와 무관하게 고정 3건**이라 목록이 길어져도 느려지지 않는다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "교안 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기수를 찾을 수 없음(다른 기관의 기수·삭제된 기수 포함) — 22차 R7"),
    })
    @GetMapping("/cohorts/{cohortId}/curricula")
    public ResponseEntity<List<CurriculumVersionResponse>> findLinkableCurricula(
            @Parameter(description = "기수 ID. 목록을 좁히지는 않지만 **존재하지 않으면 404**다(22차 R7)")
            @PathVariable UUID cohortId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        List<CurriculumVersionResponse> response = curriculumService
                .findLinkableCurriculaForCohort(cohortId, orgId).stream()
                .map(CurriculumVersionResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "쓰인 회차 | ✅ 사용 가능",
            description = """
					이 **교안**을 연결한 회차 목록을 조회한다. **재분석 경고를 좁히는 데 쓴다.**

					🔴 **13차 R1 — 경로 변수를 교안 ID로 읽도록 고쳤습니다.**
					예전에는 같은 자리를 **교안 버전 ID**로 읽었습니다. 경로 이름이 `materialId`이고
					목록 응답도 `materialId`를 주므로 화면은 교안 ID를 넣었는데, 두 ID는 값이 겹치지 않아
					**늘 빈 배열**이 됐습니다 — 목록이 `24개 회차에서 사용 중`이라고 쓰는데 상세는
					`쓰는 회차가 아직 없습니다`라고 답하던 원인입니다.

					⚠️ **11차 R3 — 응답이 문자열 배열에서 객체 배열로 바뀌었습니다.**
					예전에는 `["미니프로젝트 4차", …]` 였습니다.

					**요청**
					- materialId (경로): **교안 ID**(버전이 바뀌어도 유지되는 고정 식별자).
					  교안 목록 응답의 `materialId`와 형제 엔드포인트 `GET /curricula/{materialId}/sections`가
					  받는 값과 **같은 것**이다

					**응답 (200)** — 연결된 회차가 없으면 빈 배열

					## 목록의 `usedProjectCount`와 같은 기준이다

					이 교안의 **모든 버전**을 쓰는 회차를 모은다. 삭제된 회차는 뺀다.
					교안 목록의 `usedProjectCount`가 세는 것과 같은 모집단이라
					**`usedProjectCount`와 이 배열의 길이가 일치한다.**

					최신 버전만 보지 않는 이유는 지난 버전으로 연결된 회차가 빠지면 두 숫자가 다시
					갈리기 때문이다 — 화면이 어느 쪽을 믿어야 할지 정할 수 없게 된다.

					| 필드 | 설명 |
					|---|---|
					| `projectId` | 회차 ID. 이름을 눌러 프로젝트 화면으로 보낼 때 쓴다 |
					| `name` | 기수 안에서 붙인 회차 이름 (`미니프로젝트 4차`) |
					| `roundLabel` | 기수를 포함한 라벨 (`9기 미프 4차`) |
					| `cohortId` \\ `cohortName` | 기수 |
					| **`attendedCount`** | **응시를 시작한 인원.** 재분석 경고의 문턱 |
					| `conceptNames` | 이 회차가 쓰는 확정 검증 개념 이름 |

					## `attendedCount`가 경고를 정한다

					`0`이면 아직 아무도 응시하지 않은 회차라 **다시 분석해도 발행된 리포트가 어긋나지 않는다.**
					1 이상이면 이미 문항을 받은 학생이 있으므로, 재분석으로 섹션·쪽 번호가 달라지면
					리포트가 가리키는 교안 위치가 실제와 어긋난다.

					**완료가 아니라 시작 기준이다.** 완료만 세면 진행 중인 응시가 빠져 경고를 놓친다.

					## 빅프로젝트는 라벨이 없다

					예전에는 연결된 회차 중 빅프로젝트가 하나라도 있으면 400으로 <b>조회 전체가 실패</b>했다.
					지금은 `roundLabel`만 `null`로 두고 나머지는 그대로 준다 — 목록 하나 때문에
					화면이 통째로 비는 편이 더 나쁘다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "쓰인 회차 조회 성공"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "CURRICULUM_MATERIAL_NOT_FOUND 교안을 찾을 수 없음(다른 기관의 교안 포함)"),
    })
    @GetMapping("/curricula/{materialId}/projects")
    public ResponseEntity<List<CurriculumUsingProjectResponse>> findUsedProjects(
            @Parameter(description = "교안 ID(버전이 바뀌어도 유지되는 고정 식별자)") @PathVariable UUID materialId
    ) {
        UUID orgId = currentUserResolver.resolveCurrentUser().organizationId();
        return ResponseEntity.ok(curriculumService.findUsedProjects(materialId, orgId).stream()
                .map(CurriculumUsingProjectResponse::from)
                .toList());
    }

    @Operation(
            summary = "비교 가능한 기수 목록 | ✅ 사용 가능",
            description = """
					이 기수가 쓴 교안과 하나라도 겹치는 교안을 쓴 다른 기수 ID 목록을 조회한다.

					**요청**
					- cohortId (**쿼리**, 필수): 기준 기수 ID

					**응답 (200)**
					- UUID 배열. 겹치는 교안이 없으면 빈 배열

					⚠️ **기수 간 비교(`GET /cohorts/{cohortId}/analytics/cohort-comparison`)와 헷갈리기 쉽다**(12차 Q1).
					이름이 비슷하지만 다른 오퍼레이션이고 `cohortId`를 받는 자리도 다르다.

					| | 이 API | 기수 간 비교 |
					|---|---|---|
					| 경로 | `/curricula/comparable-cohorts` | `/cohorts/{cohortId}/analytics/cohort-comparison` |
					| `cohortId` | **쿼리** 파라미터 | **경로** 파라미터 |
					| 돌려주는 것 | 비교 후보 기수 ID 배열 | 개념별 비교 격자 |

					이쪽은 **후보를 고르기 전** 드롭다운을 채우는 용도다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비교 가능한 기수 목록 조회 성공(후보가 없으면 빈 배열)"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "COHORT_NOT_FOUND 기준 기수를 찾을 수 없음 — 「비교 대상이 없다」와 구분된다(22차 R8)"),
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
					⚠ 파일은 현재 로컬 디스크에 저장된다 — 나중에 S3 등으로 교체 예정.
					⚠ 등록 직후엔 분석이 안 된 상태다. 섹션·검증개념을 쓰려면 별도로
					`POST /curricula/{materialId}/analyses`를 호출해야 한다.

					**요청 (multipart/form-data)**
					- file (필수): PDF 파일
					- title (필수): 교안 제목
					- topic (선택): 주제

					**응답 (201)**
					- 생성된 교안 버전 정보(응답 필드는 "기수 연결 교안 목록"과 동일)

					## 🔴 22차 R2 — 코드 없는 500으로 나가던 두 실패

					`50KB짜리도 500`이라는 보고를 따라간 결과 **크기와 무관한 두 실패**가 있었다.
					둘 다 `ApiExceptionHandler`를 거치지 못해 코드 없는 500으로 나갔고, 스펙에도
					없는 상태였다. 이제 각각 코드를 가진다.

					| 코드 | 상태 | 언제 |
					|---|---|---|
					| `CURRICULUM_TITLE_DUPLICATED` | 409 | 같은 기관에 **같은 제목**의 교안이 이미 있다 |
					| `CURRICULUM_FILE_STORE_FAILED` | 503 | 저장 경로가 읽기 전용이거나 가득 찼다 |

					**제목 중복이 특히 잘 걸린다.** `uq_curriculum_material_org_id_normalized_title`이
					부분 인덱스가 아니라 전역 UNIQUE라 **논리 삭제된 교안도 제목을 계속 점유**한다.
					같은 제목으로 다시 시험하면 파일이 무엇이든 이 충돌이 난다. 화면은 이 코드로
					제목 입력란에 인라인 오류를 띄우면 된다(회차 이름의 `PROJECT_NAME_DUPLICATED`와 같다).

					> **Lambda 6MB 상한(요청서 ②)은 이 커밋의 범위가 아니다.** 본문이 base64로
					> 부풀어(×4/3) 실질 4.5MB에서 막히는 것이라 앱이 손댈 수 있는 층이 아니고,
					> presigned S3로 그 층을 비켜가는 것이 답이다 — 계약 변경이라 별건이다.
					"""
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "교안 등록 성공"),
            @ApiResponse(responseCode = "400", description = "CURRICULUM_FILE_REQUIRED 업로드할 파일이 없음 · VALIDATION_FAILED title 등 필수값 누락"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED 액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "409", description = "CURRICULUM_TITLE_DUPLICATED 같은 기관에 이미 있는 교안 제목 — 제목 입력란에 인라인 오류(22차 R2)"),
            @ApiResponse(responseCode = "503", description = "CURRICULUM_FILE_STORE_FAILED 업로드한 파일을 저장하지 못함 — 재시도 안내(22차 R2)"),
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