package com.bigproject.backend.domain.projectexecution.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcProjectMembershipQueryRepository implements ProjectMembershipQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 🔴 {@code pm.class_id IN (…)}가 이 질의의 핵심이다. 이 조건이 없던 동안 미배정 목록은
     * 기수 전원이었고, 그 목록을 그대로 먹는 자동 배분이 다른 반 사람을 끌어왔다.
     *
     * <p>{@code pm.status = 'ACTIVE'}도 함께 본다 — 프로젝트에서 빠진 사람(LEFT)은 배정 대상이
     * 아닌데 종전 질의는 그들까지 미배정으로 셌다.
     *
     * <p><b>해체된 팀의 소속은 소속이 아니다</b>(46차 R1). 해체는 팀원의 {@code to_at}을 같은
     * 트랜잭션에서 찍으므로 정상 경로에서는 결과가 같지만, 어느 한쪽만 남은 행이 생기면 그
     * 사람은 목록 어디에도 안 나오고 자동 배분·수동 배정 대상에서도 빠진다 — 되찾을 화면이
     * 없는 상태라 조회 쪽에서도 팀 생사를 함께 본다.
     */
    @Override
    public List<UnassignedMember> findUnassigned(UUID projectId, UUID orgId, Collection<UUID> classIds) {
        if (classIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(classIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        args.add(orgId);
        args.addAll(classIds);
        return jdbcTemplate.query("""
				SELECT pm.project_membership_id, pm.user_id, u.name, pm.class_id
				FROM project_membership pm
				JOIN app_user u ON u.user_id = pm.user_id
				WHERE pm.project_id = ? AND pm.org_id = ?
				  AND pm.status = 'ACTIVE'
				  AND pm.class_id IN (%s)
				  AND NOT EXISTS (
				      SELECT 1 FROM team_membership tm
				      JOIN team t ON t.team_id = tm.team_id AND t.deleted_at IS NULL
				      WHERE tm.project_membership_id = pm.project_membership_id
				        AND tm.to_at IS NULL
				  )
				ORDER BY u.name
				""".formatted(placeholders),
                (rs, i) -> new UnassignedMember(
                        UUID.fromString(rs.getString("project_membership_id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("name"),
                        UUID.fromString(rs.getString("class_id"))),
                args.toArray());
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
    public boolean belongsToProjectAndClass(UUID projectMembershipId, UUID projectId, UUID orgId, UUID classId) {
        Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM project_membership
				WHERE project_membership_id = ? AND project_id = ? AND org_id = ?
				  AND class_id = ? AND status = 'ACTIVE'
				""", Integer.class, projectMembershipId, projectId, orgId, classId);
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