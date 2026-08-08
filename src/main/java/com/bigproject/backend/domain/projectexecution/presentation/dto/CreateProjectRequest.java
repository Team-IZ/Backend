package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "프로젝트 생성 요청")
public record CreateProjectRequest(
        @Schema(description = "프로젝트명. 같은 기수 안에서 중복되면 409", example = "미프 3차")
        @NotBlank String name,

        @Schema(description = "MINI_PROJECT / BIG_PROJECT")
        @NotNull ProjectCategory category,

        @Schema(description = "시작일") @NotNull LocalDate startDate,
        @Schema(description = "종료일") @NotNull LocalDate endDate
) {
}