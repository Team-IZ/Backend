package com.bigproject.backend.domain.curriculum.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "교안 등록 요청 (multipart)")
public record RegisterCurriculumRequest(
        @Schema(description = "교안 제목", example = "AI_LLMOps") String title,
        @Schema(description = "주제(선택)", example = "AI") String topic
) {
}