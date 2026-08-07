package com.bigproject.backend.domain.usagemetering.infrastructure;

import com.bigproject.backend.domain.usagemetering.domain.StorageUsageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 저장량 스냅샷 조회.
 *
 * <p><b>v06에서 조회 방식이 바뀌었다.</b> 이전에는 {@code aggregation_status = 'SUCCEEDED'} 행만 골라 썼지만,
 * 새 스키마는 <b>실패한 측정을 아예 저장하지 않으므로</b> 이 테이블의 모든 행이 성공한 측정이다.
 * 집계 실패 여부는 이제 {@code organization_usage_snapshot.aggregation_status}에서 판정한다
 * (목업 SA-02 case 6의 USAGE_UNAVAILABLE).
 *
 * <p>또 하나 — 카테고리별로 "가장 최근 행"을 따로 고르면 서로 다른 측정 시점이 섞여 총계가 어긋난다.
 * v06에 생긴 {@code measurement_batch_id}로 <b>측정 세트 단위</b>를 먼저 고르고 그 안의 행만 쓴다.
 */
public interface StorageUsageSnapshotRepository extends JpaRepository<StorageUsageSnapshot, UUID> {

	/**
	 * 기간 내 가장 최근 측정 세트의 ID. 같은 배치에 속한 카테고리 행들이 화면 총계의 단위가 된다.
	 * 기간 내 측정이 한 번도 없으면 비어 있다.
	 */
	@Query("""
			SELECT s.measurementBatchId FROM StorageUsageSnapshot s
			WHERE s.orgId = :orgId
			  AND s.capturedAt >= :from
			  AND s.capturedAt < :to
			ORDER BY s.capturedAt DESC
			LIMIT 1
			""")
	Optional<UUID> findLatestBatchId(
			@Param("orgId") UUID orgId,
			@Param("from") Instant from,
			@Param("to") Instant to
	);

	/** 특정 측정 세트에 속한 모든 카테고리 행. */
	List<StorageUsageSnapshot> findByOrgIdAndMeasurementBatchId(UUID orgId, UUID measurementBatchId);
}
