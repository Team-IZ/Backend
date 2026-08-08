package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "프로젝트 일정 수정 요청")
public record UpdateProjectScheduleRequest(
        @Schema(description = "시작일") @NotNull LocalDate startDate,
        @Schema(description = "종료일") @NotNull LocalDate endDate
) {
}