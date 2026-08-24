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
        @Schema(description = """
                아직 어느 팀에도 속하지 않은 인원입니다. **매니저의 담당 반만** 옵니다 —
                `teams[]`와 같은 모집단입니다. 종전에는 여기만 기수 전원이라 배너 숫자가
                팀에 사람을 넣어도 줄지 않았습니다.""")
        List<UnassignedMemberResponse> unassignedMembers,
        @Schema(description = "미배정 인원 수. `unassignedMembers`의 길이와 같다") int unassignedCount
) {
    @Schema(name = "UnassignedMemberResponse", description = "아직 어느 팀에도 속하지 않은 사람 한 명")
    public record UnassignedMemberResponse(
            UUID projectMembershipId,
            UUID userId,
            String name,
            @Schema(description = "이 사람이 속한 반 ID") UUID classId,
            @Schema(description = "반 이름. 반이 지워졌으면 null", example = "B반", nullable = true) String className) {

        public static UnassignedMemberResponse from(UnassignedMember member, String className) {
            return new UnassignedMemberResponse(member.projectMembershipId(), member.userId(),
                    member.name(), member.classId(), className);
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
                .map(member -> UnassignedMemberResponse.from(member, classNames.get(member.classId())))
                .toList();
        return new TeamListResponse(teamResponses, unassignedResponses, unassignedResponses.size());
    }
}
