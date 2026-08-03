package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.LoginCohortRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcLoginCohortRepository implements LoginCohortRepository {
	private static final String FIND_LATEST_FOR_ORGANIZATION = """
			SELECT c.name
			FROM cohort c
			WHERE c.org_id = ?
				AND c.deleted_at IS NULL
			ORDER BY c.start_date DESC, c.created_at DESC, c.cohort_id DESC
			LIMIT 1
			""";

	private static final String FIND_LATEST_ASSIGNED_FOR_MANAGER = """
			SELECT c.name
			FROM cohort c
			WHERE c.org_id = ?
				AND c.deleted_at IS NULL
				AND EXISTS (
					SELECT 1
					FROM manager_assignment ma
					JOIN "class" cl ON cl.class_id = ma.class_id
					WHERE ma.manager_user_id = ?
						AND ma.org_id = c.org_id
						AND cl.org_id = c.org_id
						AND cl.cohort_id = c.cohort_id
						AND ma.status = 'ACTIVE'
						AND ma.assigned_at <= ?
						AND (ma.unassigned_at IS NULL OR ma.unassigned_at > ?)
						AND cl.lifecycle_status = 'ACTIVE'
						AND cl.deleted_at IS NULL
				)
			ORDER BY c.start_date DESC, c.created_at DESC, c.cohort_id DESC
			LIMIT 1
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<String> findLatestNameForOrganization(UUID organizationId) {
		return jdbcTemplate.queryForList(FIND_LATEST_FOR_ORGANIZATION, String.class, organizationId)
				.stream()
				.findFirst();
	}

	@Override
	public Optional<String> findLatestAssignedNameForManager(
			UUID organizationId,
			UUID managerUserId,
			Instant asOf
	) {
		Timestamp timestamp = Timestamp.from(asOf);
		return jdbcTemplate.queryForList(
				FIND_LATEST_ASSIGNED_FOR_MANAGER,
				String.class,
				organizationId,
				managerUserId,
				timestamp,
				timestamp
		).stream().findFirst();
	}
}
