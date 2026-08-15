package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "팀 정보 수정 요청")
public record UpdateTeamRequest(
        @Schema(description = "새 팀 이름", example = "3팀") @NotBlank String name
) {
}