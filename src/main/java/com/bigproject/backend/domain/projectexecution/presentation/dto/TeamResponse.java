package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.Team;
import com.bigproject.backend.domain.projectexecution.domain.TeamStatus;
import com.bigproject.backend.domain.projectexecution.infrastructure.ProjectMembershipQueryRepository.TeamMember;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "팀 정보")
public record TeamResponse(
        @Schema(description = "팀 ID") UUID teamId,
        @Schema(description = "이 팀이 속한 반 ID") UUID classId,
        @Schema(description = """
                반 이름입니다. **팀 번호만으로는 팀을 구분할 수 없습니다**(30차 R4) — 번호가 반마다
                1부터 다시 시작해서 한 기수에 `1팀`이 반 수만큼 있습니다. 반이 지워졌으면 null입니다.
                """, example = "C반", nullable = true)
        String className,
        @Schema(description = "표시용 번호이며 반 안에서만 유일합니다.", example = "3") String teamNumber,
        @Schema(description = "팀 이름") String name,
        @Schema(description = "DRAFT(편성 중) · CONFIRMED(확정됨)") TeamStatus status,
        @Schema(description = "현재 유효 인원 수이며 `members`의 길이와 같습니다.") int memberCount,
        @Schema(description = """
                지금 이 팀에 속한 사람들이며 이름 오름차순입니다. 편성 화면이 사람을 끌어다 옮기는
                자리에 씁니다 — `unassignedMembers`와 **같은 모양**이라 한 컴포넌트로 다룰 수 있습니다.

                팀을 막 만든 직후처럼 아직 아무도 없으면 빈 배열입니다.
                """)
        List<TeamMemberResponse> members
) {

    @Schema(name = "TeamMemberResponse", description = "팀에 배정된 사람 한 명")
    public record TeamMemberResponse(
            @Schema(description = "배정·해제에 쓰는 프로젝트 참여 ID") UUID projectMembershipId,
            @Schema(description = "사용자 ID") UUID userId,
            @Schema(description = "이름", example = "김민준") String name
    ) {
        public static TeamMemberResponse from(TeamMember member) {
            return new TeamMemberResponse(member.projectMembershipId(), member.userId(), member.name());
        }
    }

    public static TeamResponse from(Team team, String className, List<TeamMember> members) {
        List<TeamMemberResponse> memberResponses = members.stream()
                .map(TeamMemberResponse::from)
                .toList();
        return new TeamResponse(team.getTeamId(), team.getClassId(), className,
                team.getTeamNumber(), team.getName(), team.getStatus(),
                memberResponses.size(), memberResponses);
    }

    /** 방금 만들어 아직 아무도 없는 팀. 인원을 세러 갈 것이 없다. */
    public static TeamResponse empty(Team team, String className) {
        return from(team, className, List.of());
    }
}
