package com.bigproject.backend.domain.usagemetering.infrastructure;

import com.bigproject.backend.domain.usagemetering.domain.AiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AiUsageRepository extends JpaRepository<AiUsage, UUID> {

	// 기간 내 AI 사용 내역을 조회한다. v07에서 모델이 FK가 아니라 model_code 문자열이 되어 조인이 필요 없다
	// (표시명이 필요하면 호출부가 코드 목록으로 ai_model을 한 번에 조회한다).
	@Query("""
			SELECT u FROM AiUsage u
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
	// v06: 단가 미설정(UNPRICED) 호출은 비용을 알 수 없으므로 합계에서 제외한다 — 0으로 더하면 청구액이 실제보다 작아 보인다.
	// 결과 프로젝션(OrgAiCostTotal)은 최상위 클래스여야 한다 — Hibernate HQL 파서가 중첩 클래스를
	// "SELECT new ...(...)" 생성자 표현식 대상으로 못 찾는 문제가 있다.
	@Query("""
			SELECT new com.bigproject.backend.domain.usagemetering.infrastructure.OrgAiCostTotal(
				u.orgId, SUM(COALESCE(u.actualCost, u.estimatedCost))
			)
			FROM AiUsage u
			WHERE u.orgId IN :orgIds
			  AND u.occurredAt >= :from
			  AND u.occurredAt < :to
			  AND u.pricingStatus <> :unpriced
			GROUP BY u.orgId
			""")
	List<OrgAiCostTotal> sumCostByOrgId(
			@Param("orgIds") Collection<UUID> orgIds,
			@Param("from") Instant from,
			@Param("to") Instant to,
			@Param("unpriced") AiUsage.PricingStatus unpriced
	);

	/** 단가 미설정을 제외한 비용 합계. 호출부가 매번 enum을 넘기지 않도록 감싼다. */
	default List<OrgAiCostTotal> sumPricedCostByOrgId(Collection<UUID> orgIds, Instant from, Instant to) {
		return sumCostByOrgId(orgIds, from, to, AiUsage.PricingStatus.UNPRICED);
	}
}
