package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.application.CurriculumService.LinkableCurriculum;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "연결 가능한 교안 버전")
public record CurriculumVersionResponse(
        @Schema(description = "curriculum_version_id — 프론트 Curriculum.id와 동일") UUID versionId,
        @Schema(description = "교안 원장 ID") UUID materialId,
        @Schema(description = "버전 번호", example = "2") Integer versionNo,
        @Schema(description = "원본 파일명", example = "AI_LLMOps_v2.pdf") String originalFileName,

        /*
         * 18차 R6 — CurriculumCatalogItem 과 선언이 어긋나 있었다. 그쪽은 nullable 로 적혀 있고
         * 실제 응답도 null 이 오는데(실측 7종 중 5종) 이 스키마만 아니라고 선언하고 있었다.
         * 프론트는 스펙에서 타입을 생성하므로 스펙이 곧 계약이다.
         */
        @Schema(description = "페이지 수. 분석 전이거나 확정되지 않았으면 null", example = "84", nullable = true)
        Integer pageCount,

        /*
         * 18차 R2 — 화면이 pageCount == null 로 분석 여부를 추측하고 있었다. 그건 "쪽수를 아직
         * 모른다"는 뜻이지 "분석 중"이 아니고, 분석 실패와도 구분되지 않는다.
         */
        @Schema(description = """
                가장 최근 분석 **시도**의 상태. 회차 생성 모달의 `분석 중`·`분석 실패` 배지 근거다.

                **한 번도 분석하지 않은 교안은 `null`이다** — 실패와 구분해야 해서 값을 만들어 넣지 않는다.

                화면은 이렇게 접으면 된다. `SUCCEEDED` → 고를 수 있음 ·
                `PENDING`/`RUNNING` → `분석 중 — 끝나면 고를 수 있습니다` ·
                `FAILED` → `분석 실패 — 다시 올려 주세요` · `null` → `분석 전`""",
                nullable = true)
        CurriculumAnalysisStatus analysisStatus,

        @Schema(description = """
                승인된(ACTIVE) 가르친 항목 수. **고르기 전에** 이 교안에서 검증 개념 3건을 뽑을 수
                있는지 알 수 있다 — 종전에는 골라 봐야 알았다.

                `GET /projects/{projectId}/concept-candidates`가 세는 것과 같은 값이라 두 화면이
                다른 수를 말하지 않는다.""",
                example = "34")
        int teachesCount,

        @Schema(description = "등록 시각") OffsetDateTime createdAt
) {

    /**
     * 분석 상태를 모르는 자리에서 쓴다 — 교안을 <b>방금 등록한</b> 응답이며, 그 순간에는
     * 분석이 시작조차 하지 않았으므로 {@code null}과 {@code 0}이 사실이다.
     */
    public static CurriculumVersionResponse from(CurriculumVersion version) {
        return new CurriculumVersionResponse(
                version.getVersionId(),
                version.getMaterialId(),
                version.getVersionNo(),
                version.getOriginalFileName(),
                version.getPageCount(),
                null,
                0,
                version.getCreatedAt()
        );
    }

    public static CurriculumVersionResponse from(LinkableCurriculum linkable) {
        CurriculumVersion version = linkable.version();
        return new CurriculumVersionResponse(
                version.getVersionId(),
                version.getMaterialId(),
                version.getVersionNo(),
                version.getOriginalFileName(),
                version.getPageCount(),
                linkable.analysisStatus(),
                linkable.teachesCount(),
                version.getCreatedAt()
        );
    }
}
