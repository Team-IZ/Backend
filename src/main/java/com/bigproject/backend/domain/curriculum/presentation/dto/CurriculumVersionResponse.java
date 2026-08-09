package com.bigproject.backend.domain.curriculum.presentation.dto;

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
        @Schema(description = "페이지 수") Integer pageCount,
        @Schema(description = "등록 시각") OffsetDateTime createdAt
) {
    public static CurriculumVersionResponse from(CurriculumVersion version) {
        return new CurriculumVersionResponse(
                version.getVersionId(),
                version.getMaterialId(),
                version.getVersionNo(),
                version.getOriginalFileName(),
                version.getPageCount(),
                version.getCreatedAt()
        );
    }
}