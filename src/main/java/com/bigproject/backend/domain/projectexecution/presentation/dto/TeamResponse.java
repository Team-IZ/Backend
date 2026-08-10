package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.Team;
import com.bigproject.backend.domain.projectexecution.domain.TeamStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "팀 정보")
public record TeamResponse(
        @Schema(description = "팀 ID") UUID teamId,
        @Schema(description = "표시용 번호", example = "3") String teamNumber,
        @Schema(description = "팀 이름") String name,
        @Schema(description = "DRAFT(편성 중) · CONFIRMED(확정됨)") TeamStatus status,
        @Schema(description = "현재 유효 인원 수") int memberCount
) {
    public static TeamResponse from(Team team, int memberCount) {
        return new TeamResponse(team.getTeamId(), team.getTeamNumber(), team.getName(),
                team.getStatus(), memberCount);
    }
}