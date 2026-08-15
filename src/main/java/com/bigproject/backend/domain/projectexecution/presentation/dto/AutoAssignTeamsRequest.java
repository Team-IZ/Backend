package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "팀 자동 배분 요청")
public record AutoAssignTeamsRequest(
        @Schema(description = "팀 하나의 목표 인원", example = "3") @NotNull @Min(1) Integer teamSize,
        @Schema(description = "true면 직전 회차 도달 단계 기준 실력 섞기, false면 무작위", example = "false")
        @NotNull Boolean skillBalanced
) {
}