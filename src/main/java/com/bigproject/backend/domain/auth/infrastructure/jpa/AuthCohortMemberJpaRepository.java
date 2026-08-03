package com.bigproject.backend.domain.auth.infrastructure.jpa;

import com.bigproject.backend.domain.cohort.domain.CohortMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuthCohortMemberJpaRepository extends JpaRepository<CohortMember, UUID> {
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE cohort_member
			SET status = 'ACTIVE', joined_at = :activatedAt
			WHERE user_id = :userId
				AND status = 'INVITED'
			""", nativeQuery = true)
	int activateByUserId(@Param("userId") UUID userId, @Param("activatedAt") Instant activatedAt);
}
