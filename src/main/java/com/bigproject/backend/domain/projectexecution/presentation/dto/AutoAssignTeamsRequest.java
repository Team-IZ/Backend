package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "팀 자동 배분 요청")
public record AutoAssignTeamsRequest(
        @Schema(description = """
                배분할 **반 ID**입니다. 이 반의 미배정 인원만 대상이고, 만들어지는 팀도 이 반의 팀입니다.

                🔴 **생성 팀 수가 `ceil(그 반 인원 / teamSize)`입니다.** 종전에는 기수 전원이 대상이라
                7기에서 한 반에 63팀이 만들어질 수 있었습니다. B반 25명 · `teamSize: 4`면 7팀입니다.

                🔴 **"팀이 하나도 없다"는 조건도 이 반 기준입니다.** 종전에는 프로젝트 전역이라
                다른 반에 팀이 있으면 내 반 자동 배분이 409로 막혔습니다.""",
                example = "d047bb3a-db7d-59e5-bd23-118340d6075a")
        @NotNull UUID classId,

        @Schema(description = "팀 하나의 목표 인원", example = "3") @NotNull @Min(1) Integer teamSize,

        @Schema(description = "true면 직전 회차 도달 단계 기준 실력 섞기, false면 무작위", example = "false")
        @NotNull Boolean skillBalanced
) {
}
