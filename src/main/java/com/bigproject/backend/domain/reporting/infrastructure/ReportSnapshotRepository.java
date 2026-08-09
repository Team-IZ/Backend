package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, UUID> {

	/** 리포트 1건의 현재 본문. 활성 스냅샷은 리포트당 0 또는 1건이다. */
	Optional<ReportSnapshot> findByReportIdAndIsActiveTrue(UUID reportId);

	/**
	 * TR-04는 회차 전량의 본문을 한 번에 내려주므로(프론트 {@code getReports()} 1회 호출)
	 * 리포트 개수만큼 조회를 반복하면 N+1이 된다. 리포트 id를 모아 한 번에 가져온다.
	 */
	List<ReportSnapshot> findByReportIdInAndIsActiveTrue(Collection<UUID> reportIds);
}
