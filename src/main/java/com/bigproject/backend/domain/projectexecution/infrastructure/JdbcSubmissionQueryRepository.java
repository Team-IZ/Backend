package com.bigproject.backend.domain.projectexecution.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcSubmissionQueryRepository implements SubmissionQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean hasAcceptedSubmission(UUID teamId, UUID orgId) {
        Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM submission
				WHERE team_id = ? AND org_id = ? AND status = 'ACCEPTED'
				""", Integer.class, teamId, orgId);
        return count != null && count > 0;
    }

    @Override
    public Set<UUID> findTeamIdsWithAcceptedSubmission(Collection<UUID> teamIds, UUID orgId) {
        if (teamIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(teamIds.size(), "?"));
        List<Object> args = new ArrayList<>(teamIds);
        args.add(orgId);
        return new HashSet<>(jdbcTemplate.query("""
				SELECT DISTINCT team_id FROM submission
				WHERE team_id IN (%s) AND org_id = ? AND status = 'ACCEPTED'
				""".formatted(placeholders),
                (rs, rowNum) -> rs.getObject("team_id", UUID.class),
                args.toArray()));
    }
}
