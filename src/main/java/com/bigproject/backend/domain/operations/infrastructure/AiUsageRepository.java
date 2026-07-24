package com.bigproject.backend.domain.operations.infrastructure;

import com.bigproject.backend.domain.operations.domain.AiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

	// 여러 기관의 기간 내 AI 비용 합계를 기관별로 한 번에 조회한다(organization 목록 조회에서 기관 수만큼 매번
	// 따로 합산하는 N+1을 피하기 위함). actualCost가 확정되기 전에는 estimatedCost를 대신 사용한다(AiUsage.resolveCost()와 동일 규칙).
	// 결과 프로젝션(OrgAiCostTotal)은 최상위 클래스여야 한다 — Hibernate HQL 파서가 중첩 클래스를
	// "SELECT new ...(...)" 생성자 표현식 대상으로 못 찾는 문제가 있다.
	@Query("""
			SELECT new com.bigproject.backend.domain.operations.infrastructure.OrgAiCostTotal(
				u.orgId, SUM(COALESCE(u.actualCost, u.estimatedCost))
			)
			FROM AiUsage u
			WHERE u.orgId IN :orgIds
			  AND u.occurredAt >= :from
			  AND u.occurredAt < :to
			GROUP BY u.orgId
			""")
	List<OrgAiCostTotal> sumCostByOrgId(
			@Param("orgIds") Collection<UUID> orgIds,
			@Param("from") Instant from,
			@Param("to") Instant to
	);
}
