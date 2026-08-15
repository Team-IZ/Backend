package com.bigproject.backend.domain.projectexecution.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
}