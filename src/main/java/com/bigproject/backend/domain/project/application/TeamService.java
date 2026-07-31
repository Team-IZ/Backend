package com.bigproject.backend.domain.project.application;

import com.bigproject.backend.domain.project.domain.AssignmentMethod;
import com.bigproject.backend.domain.project.domain.Team;
import com.bigproject.backend.domain.project.domain.TeamMembership;
import com.bigproject.backend.domain.project.infrastructure.TeamMembershipRepository;
import com.bigproject.backend.domain.project.infrastructure.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)

public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;

    @Transactional
    public Team createTeam(UUID projectId, UUID orgId, String name, UUID actorUserId) {
        Team team = Team.builder()
                .projectId(projectId)
                .orgId(orgId)
                .name(name)
                .createdBy(actorUserId)
                .build();
        return teamRepository.save(team);
    }

    public Team findTeam(UUID teamId, UUID orgId) {
        return teamRepository.findByTeamIdAndOrgId(teamId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다."));
    }

    public List<Team> findTeams(UUID projectId, UUID orgId) {
        return teamRepository.findByProjectIdAndOrgId(projectId, orgId);
    }

    // 특정 사람을 이 팀으로 배정. 이미 다른 팀에 있으면 그 배정부터 닫고 새로 연다
    @Transactional
    public TeamMembership assignMember(UUID teamId, UUID projectMembershipId,
                                       AssignmentMethod method, UUID actorUserId) {

        OffsetDateTime now = OffsetDateTime.now();
        teamMembershipRepository.findEffectiveAt(projectMembershipId, now)
                .ifPresent(existing -> existing.unassign(now));

        // 기존 배정을 닫는 UPDATE를 새 배정 INSERT보다 먼저 내보낸다.
        // Hibernate는 flush 시 INSERT를 UPDATE보다 먼저 실행하므로, 이 줄이 없으면
        // 유니크 인덱스가 붙는 순간 배정이 실패한다 (ClassroomService와 동일 이슈)
        teamMembershipRepository.flush();

        TeamMembership membership = TeamMembership.builder()
                .teamId(teamId)
                .projectMembershipId(projectMembershipId)
                .orgId(teamRepository.findById(teamId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다."))
                        .getOrgId())
                .assignmentMethod(method)
                .fromAt(now)
                .assignedBy(actorUserId)
                .build();

        return teamMembershipRepository.save(membership);
    }

    @Transactional
    public void disbandTeam(UUID teamId, UUID orgId) {
        Team team = findTeam(teamId, orgId);
        team.disband();
    }
}
