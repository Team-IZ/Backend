package com.bigproject.backend.domain.projectexecution.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcProjectMembershipQueryRepository implements ProjectMembershipQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<UnassignedMember> findUnassigned(UUID projectId, UUID orgId) {
        return jdbcTemplate.query("""
				SELECT pm.project_membership_id, pm.user_id, u.name
				FROM project_membership pm
				JOIN app_user u ON u.user_id = pm.user_id
				WHERE pm.project_id = ? AND pm.org_id = ?
				  AND NOT EXISTS (
				      SELECT 1 FROM team_membership tm
				      WHERE tm.project_membership_id = pm.project_membership_id
				        AND tm.to_at IS NULL
				  )
				ORDER BY u.name
				""",
                (rs, i) -> new UnassignedMember(
                        UUID.fromString(rs.getString("project_membership_id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("name")),
                projectId, orgId);
    }

    /**
     * {@code to_at IS NULL}이 지금 유효한 소속이다. team_membership은 이동 이력이 쌓이는 표라
     * 이 조건을 빼면 옮겨 간 사람이 옛 팀에도 남는다.
     */
    @Override
    public List<TeamMember> findMembersByTeamIds(List<UUID> teamIds) {
        if (teamIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(teamIds.size(), "?"));
        return jdbcTemplate.query("""
					SELECT tm.team_id, pm.project_membership_id, pm.user_id, u.name
					FROM team_membership tm
					JOIN project_membership pm ON pm.project_membership_id = tm.project_membership_id
					JOIN app_user u ON u.user_id = pm.user_id
					WHERE tm.to_at IS NULL
					  AND tm.team_id IN (%s)
					ORDER BY u.name
					""".formatted(placeholders),
                (rs, i) -> new TeamMember(
                        UUID.fromString(rs.getString("team_id")),
                        UUID.fromString(rs.getString("project_membership_id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("name")),
                teamIds.toArray());
    }

    @Override
    public boolean belongsToProject(UUID projectMembershipId, UUID projectId, UUID orgId) {
        Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM project_membership
				WHERE project_membership_id = ? AND project_id = ? AND org_id = ?
				""", Integer.class, projectMembershipId, projectId, orgId);
        return count != null && count > 0;
    }

    @Override
    public Optional<UUID> findProjectMembershipId(UUID projectId, UUID orgId, UUID traineeId) {
        return jdbcTemplate.query("""
				SELECT project_membership_id FROM project_membership
				WHERE project_id = ? AND org_id = ? AND user_id = ?
				""",
                        (rs, i) -> UUID.fromString(rs.getString("project_membership_id")),
                        projectId, orgId, traineeId)
                .stream().findFirst();
    }
}