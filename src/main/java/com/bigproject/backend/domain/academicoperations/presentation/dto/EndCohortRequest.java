package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "기수 종료 요청")
public record EndCohortRequest(
        @Schema(description = "종료 사유. 빈 문자열이면 400", example = "교육 과정 정상 종료")
        @NotBlank String reason
) {
}