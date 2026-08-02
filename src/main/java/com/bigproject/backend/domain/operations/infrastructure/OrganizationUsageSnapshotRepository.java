package com.bigproject.backend.domain.operations.infrastructure;

import com.bigproject.backend.domain.operations.domain.OrganizationUsageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 기관 기간 사용량 스냅샷 조회. v06 신규 테이블이며, 사용량 화면은 이 스냅샷을 <b>우선</b> 조회한다.
 *
 * <p>같은 기간에 대해 재집계가 일어나면 행이 여러 개 쌓이므로 항상 {@code as_of_at}이 가장 최신인 1건을 쓴다.
 * 성공/실패를 가리지 않고 최신 1건을 가져오는 이유는, <b>가장 최근 시도가 실패였다면 그 사실을 알려야</b> 하기 때문이다
 * (목업 SA-02 case 6). 성공분만 골라 오면 실패를 조용히 숨기게 된다.
 */
public interface OrganizationUsageSnapshotRepository extends JpaRepository<OrganizationUsageSnapshot, UUID> {

	@Query("""
			SELECT s FROM OrganizationUsageSnapshot s
			WHERE s.orgId = :orgId
			  AND s.periodStartAt = :periodStart
			  AND s.periodEndAt = :periodEnd
			ORDER BY s.asOfAt DESC
			LIMIT 1
			""")
	Optional<OrganizationUsageSnapshot> findLatestByPeriod(
			@Param("orgId") UUID orgId,
			@Param("periodStart") Instant periodStart,
			@Param("periodEnd") Instant periodEnd
	);
}
