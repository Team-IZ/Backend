package com.bigproject.backend.domain.curriculum.presentation;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import com.bigproject.backend.domain.curriculum.presentation.dto.CurriculumVersionResponse;
import com.bigproject.backend.global.security.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
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
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
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
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
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
            @ApiResponse(responseCode = "400", description = "파일이 없거나 필수값 누락"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
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
            @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "교안을 찾을 수 없음"),
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