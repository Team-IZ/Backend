package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.Team;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectMembershipQueryRepository.TeamMember;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectMembershipQueryRepository.UnassignedMember;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "팀 목록 응답")
public record TeamListResponse(
        @Schema(description = "팀 목록입니다. **매니저의 담당 반만** 옵니다(30차 R3).") List<TeamResponse> teams,
        @Schema(description = "아직 어느 팀에도 속하지 않은 인원 목록") List<UnassignedMemberResponse> unassignedMembers,
        @Schema(description = "미배정 인원 수. `unassignedMembers`의 길이와 같다") int unassignedCount
) {
    public record UnassignedMemberResponse(UUID projectMembershipId, UUID userId, String name) {
        public static UnassignedMemberResponse from(UnassignedMember member) {
            return new UnassignedMemberResponse(member.projectMembershipId(), member.userId(), member.name());
        }
    }

    public static TeamListResponse from(List<Team> teams,
                                        Map<UUID, String> classNames,
                                        Map<UUID, List<TeamMember>> membersByTeam,
                                        List<UnassignedMember> unassigned) {
        List<TeamResponse> teamResponses = teams.stream()
                .map(team -> TeamResponse.from(team,
                        classNames.get(team.getClassId()),
                        membersByTeam.getOrDefault(team.getTeamId(), List.of())))
                .toList();
        List<UnassignedMemberResponse> unassignedResponses = unassigned.stream()
                .map(UnassignedMemberResponse::from)
                .toList();
        return new TeamListResponse(teamResponses, unassignedResponses, unassignedResponses.size());
    }
}
