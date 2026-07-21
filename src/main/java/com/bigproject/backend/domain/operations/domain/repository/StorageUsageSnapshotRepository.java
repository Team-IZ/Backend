package com.bigproject.backend.domain.operations.domain.repository;

import com.bigproject.backend.domain.operations.domain.StorageUsageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StorageUsageSnapshotRepository extends JpaRepository<StorageUsageSnapshot, UUID> {

	// 특정 기관·기간(월)에 수집된, 집계에 성공(SUCCEEDED)한 스냅샷만 조회한다. 실패(FAILED) 스냅샷은 집계에서 제외한다.
	List<StorageUsageSnapshot> findByOrgIdAndAggregationStatusAndAsOfAtGreaterThanEqualAndAsOfAtLessThan(
			UUID orgId,
			StorageUsageSnapshot.AggregationStatus aggregationStatus,
			Instant from,
			Instant to
	);
}
