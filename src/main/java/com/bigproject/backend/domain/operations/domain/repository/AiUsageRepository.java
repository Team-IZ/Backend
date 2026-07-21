package com.bigproject.backend.domain.operations.domain.repository;

import com.bigproject.backend.domain.operations.domain.AiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AiUsageRepository extends JpaRepository<AiUsage, UUID> {

	// 기간 내 AI 사용 내역을 모델 정보와 함께 한 번에 조회한다(N+1 방지를 위한 JOIN FETCH).
	@Query("""
			SELECT u FROM AiUsage u
			JOIN FETCH u.model
			WHERE u.orgId = :orgId
			  AND u.occurredAt >= :from
			  AND u.occurredAt < :to
			""")
	List<AiUsage> findByOrgIdAndOccurredAtBetween(
			@Param("orgId") UUID orgId,
			@Param("from") Instant from,
			@Param("to") Instant to
	);
}
