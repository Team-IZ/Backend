package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** {@code report_generation_run} 읽기·쓰기. */
public interface ReportGenerationRunRepository extends JpaRepository<ReportGenerationRun, UUID> {

	/**
	 * 이 리포트의 다음 {@code execution_no}. 첫 시도는 1, 재시도는 직전 최대값 + 1이다.
	 *
	 * <p>이 값이 {@code idempotency_key}의 일부라 <b>재시도마다 달라져야 한다.</b> 고정하면
	 * {@code uq_report_generation_run_idempotency_key}가 재시도 자체를 막는다.
	 */
	@Query("SELECT MAX(r.executionNo) FROM ReportGenerationRun r WHERE r.reportId = :reportId")
	Integer findMaxExecutionNo(@Param("reportId") UUID reportId);

	/**
	 * 아직 끝나지 않은 실행. 폴링이 item을 다 처리한 뒤 run을 닫을 때 쓴다.
	 *
	 * <p>{@code RETRYING}은 넣지 않는다 — 재시도는 새 행으로 남기므로 그 상태로 전이하는 경로가 없다
	 * ({@code ReportGenerationRunStatus} javadoc).
	 */
	@Query("SELECT r FROM ReportGenerationRun r WHERE r.status IN "
			+ "(com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus.QUEUED, "
			+ "com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus.RUNNING)")
	List<ReportGenerationRun> findActive();
}
