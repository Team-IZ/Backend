package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "프로젝트 요구사항 전체 교체 요청")
public record ReplaceRequirementsRequest(
        @Schema(description = "요구사항 문구 목록. 보낸 목록이 최종 상태가 된다") List<String> requirementTitles
) {
}