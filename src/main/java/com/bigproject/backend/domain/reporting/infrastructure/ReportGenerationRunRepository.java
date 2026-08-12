package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportGenerationRun;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationTriggerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
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

	/**
	 * 이 리포트에 <b>아직 안 끝난 실행</b>이 있으면 돌려준다. 문제 단위 dispatch가 쓴다.
	 *
	 * <h2>왜 필요한가</h2>
	 *
	 * <p>문제가 끝날 때마다 요청을 보내지만 <b>실행(run)은 학생당 하나</b>여야 한다. 문제마다 run을
	 * 만들면 {@code uq_report_snapshot_generation_run_id}(run 1건당 스냅샷 1건) 때문에 스냅샷이
	 * 문제 수만큼 생기고, 리포트는 회차당 1건이라({@code uq_report_active_user}) 그걸 다시 합치는
	 * 단계가 필요해진다.
	 *
	 * <p>그래서 첫 문제에서 run을 만들고, 이후 문제는 <b>같은 run에 item만 붙인다.</b>
	 *
	 * <p>{@code uq_report_generation_run_active}가 {@code (report_id, trigger_type)}에
	 * {@code status IN (QUEUED, RUNNING, RETRYING)} 조건으로 걸려 있어 <b>DB도 하나만 허용한다</b> —
	 * 이 조회는 그 인덱스와 같은 것을 본다.
	 */
	@Query("SELECT r FROM ReportGenerationRun r WHERE r.reportId = :reportId "
			+ "AND r.triggerType = :triggerType AND r.status IN "
			+ "(com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus.QUEUED, "
			+ "com.bigproject.backend.domain.reporting.domain.ReportGenerationRunStatus.RUNNING)")
	Optional<ReportGenerationRun> findActiveByReportIdAndTriggerType(
			@Param("reportId") UUID reportId,
			@Param("triggerType") ReportGenerationTriggerType triggerType);
}
