package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 프로젝트 생성/구성 화면의 "연결할 교안" 후보 목록 응답 항목.
 * 프론트 계약상 Curriculum.id = curriculum_version_id (material_id 아님) — 사용자 메모리 참고.
 */
@Schema(description = "연결 가능한 교안 버전")
public record CurriculumVersionResponse(
        @Schema(description = "curriculum_version_id — 프론트 Curriculum.id와 동일") UUID versionId,
        UUID materialId,
        Integer versionNo,
        String originalFileName,
        Integer pageCount,
        OffsetDateTime createdAt
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