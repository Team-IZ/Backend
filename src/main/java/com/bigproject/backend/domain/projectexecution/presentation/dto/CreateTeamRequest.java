package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "팀 생성 요청")
public record CreateTeamRequest(
        @Schema(description = """
                팀을 만들 **반 ID**입니다. 팀은 반별로 짜므로 어느 반의 팀인지를 요청이 정합니다.

                | 상황 | 응답 |
                | --- | --- |
                | 담당하지 않는 반 | 403 `CLASS_NOT_MANAGED` |
                | 이 프로젝트의 기수에 없는 반 | 404 `CLASS_NOT_FOUND` |
                | 값이 없음 | 400 `VALIDATION_FAILED` |

                반 목록은 `GET /cohorts/{cohortId}/classrooms`가 줍니다 — 매니저에게는 담당 반만
                내려가므로 그대로 드롭다운에 쓰면 됩니다. 담당 반이 하나뿐이면 선택 UI 없이
                그 값을 실어 보내면 됩니다.""",
                example = "d047bb3a-db7d-59e5-bd23-118340d6075a")
        @NotNull UUID classId,

        @Schema(description = "팀 이름. 같은 반 안에서 유일해야 합니다", example = "3팀") @NotBlank String name
) {
}
