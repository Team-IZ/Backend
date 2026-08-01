package com.bigproject.backend.domain.operations.infrastructure;

import com.bigproject.backend.domain.operations.domain.StorageUsageSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StorageUsageSnapshotRepository extends JpaRepository<StorageUsageSnapshot, UUID> {

	List<StorageUsageSnapshot> findByOrgIdAndCapturedAtGreaterThanEqualAndCapturedAtLessThan(
			UUID orgId, Instant from, Instant to);
}
