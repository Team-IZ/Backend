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

        // 응답과 같은 공유 스키마(ProjectCategory)를 참조한다 — 값 목록을 요청·응답에 따로 적어 두면
        // 한쪽에만 값이 늘었을 때 갈린다.
        @NotNull ProjectCategory category,

        @Schema(description = "시작일") @NotNull LocalDate startDate,
        @Schema(description = "종료일") @NotNull LocalDate endDate
) {
}