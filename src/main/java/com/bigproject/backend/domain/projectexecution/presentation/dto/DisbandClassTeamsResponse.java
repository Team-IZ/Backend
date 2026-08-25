package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.TeamService.DisbandResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "반 통째로 해체 응답(46차 R3)")
public record DisbandClassTeamsResponse(
        @Schema(description = "해체한 팀 수. 이미 빈 반이었으면 0입니다(에러가 아닙니다).", example = "6")
        int disbandedTeamCount,
        @Schema(description = """
                미배정으로 돌아간 인원 수입니다. 목록을 다시 받아서는 알 수 없는 값이라
                (해체 전 인원을 모릅니다) 여기에 실어 보냅니다 — 「6개 팀 25명이 미배정으로
                돌아갔습니다」 같은 안내에 쓰시면 됩니다.""", example = "25")
        int unassignedMemberCount
) {

    public static DisbandClassTeamsResponse from(DisbandResult result) {
        return new DisbandClassTeamsResponse(result.disbandedTeamCount(), result.unassignedMemberCount());
    }
}
