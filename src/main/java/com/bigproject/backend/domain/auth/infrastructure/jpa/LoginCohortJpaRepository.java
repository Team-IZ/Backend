package com.bigproject.backend.domain.auth.infrastructure.jpa;

import com.bigproject.backend.domain.cohort.domain.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LoginCohortJpaRepository extends JpaRepository<Cohort, UUID> {
	@Query(value = """
			SELECT c.name
			FROM cohort c
			WHERE c.org_id = :organizationId
				AND c.deleted_at IS NULL
			ORDER BY c.start_date DESC, c.created_at DESC, c.cohort_id DESC
			LIMIT 1
			""", nativeQuery = true)
	List<String> findLatestName(@Param("organizationId") UUID organizationId);

	@Query(value = """
			SELECT c.name
			FROM cohort c
			WHERE c.org_id = :organizationId
				AND c.deleted_at IS NULL
				AND EXISTS (
					SELECT 1
					FROM manager_assignment ma
					JOIN "class" cl ON cl.class_id = ma.class_id
					WHERE ma.manager_user_id = :managerUserId
						AND ma.org_id = c.org_id
						AND cl.org_id = c.org_id
						AND cl.cohort_id = c.cohort_id
						AND ma.status = 'ACTIVE'
						AND ma.assigned_at <= :asOf
						AND (ma.unassigned_at IS NULL OR ma.unassigned_at > :asOf)
						AND cl.lifecycle_status = 'ACTIVE'
						AND cl.deleted_at IS NULL
				)
			ORDER BY c.start_date DESC, c.created_at DESC, c.cohort_id DESC
			LIMIT 1
			""", nativeQuery = true)
	List<String> findLatestAssignedName(
			@Param("organizationId") UUID organizationId,
			@Param("managerUserId") UUID managerUserId,
			@Param("asOf") Instant asOf
	);
}
