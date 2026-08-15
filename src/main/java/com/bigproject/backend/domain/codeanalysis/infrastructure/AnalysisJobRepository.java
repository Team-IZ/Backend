package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

	/**
	 * AI에 접수된 활성 job의 상태만 갱신한다.
	 *
	 * <p>{@code externalJobId}는 SET 대상이 아니라 동시성 검증 조건으로만 쓴다. 따라서 폴링 응답을
	 * 저장하는 어떤 경우에도 {@code external_job_id}를 NULL이나 다른 값으로 바꿀 SQL이 만들어지지
	 * 않는다. 이미 종료됐거나 외부 ID가 달라진 행이면 0을 반환해 오래된 폴링 응답을 버린다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE AnalysisJob job
			   SET job.status = :status,
			       job.startedAt = :startedAt,
			       job.completedAt = :completedAt,
			       job.failureReason = :failureReason,
			       job.failureCode = :failureCode
			 WHERE job.jobId = :jobId
			   AND job.externalJobId = :externalJobId
			   AND job.status IN :activeStatuses
			""")
	int transitionActiveJob(
			@Param("jobId") UUID jobId,
			@Param("externalJobId") UUID externalJobId,
			@Param("status") AnalysisJobStatus status,
			@Param("startedAt") Instant startedAt,
			@Param("completedAt") Instant completedAt,
			@Param("failureReason") String failureReason,
			@Param("failureCode") AnalysisFailureCode failureCode,
			@Param("activeStatuses") Collection<AnalysisJobStatus> activeStatuses);

	/**
	 * 과거 데이터처럼 외부 ID 없이 남은 활성 job을 실패로 닫는다.
	 *
	 * <p>정상 접수 건은 202 응답의 ID를 포함해 최초 INSERT하므로 이 경로를 타지 않는다. 외부 ID가
	 * 실제로 NULL인 행만 대상으로 하며, 역시 그 컬럼은 SET 절에 포함하지 않는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE AnalysisJob job
			   SET job.status = :status,
			       job.startedAt = :startedAt,
			       job.completedAt = :completedAt,
			       job.failureReason = :failureReason,
			       job.failureCode = :failureCode
			 WHERE job.jobId = :jobId
			   AND job.externalJobId IS NULL
			   AND job.status IN :activeStatuses
			""")
	int transitionActiveJobWithoutExternalId(
			@Param("jobId") UUID jobId,
			@Param("status") AnalysisJobStatus status,
			@Param("startedAt") Instant startedAt,
			@Param("completedAt") Instant completedAt,
			@Param("failureReason") String failureReason,
			@Param("failureCode") AnalysisFailureCode failureCode,
			@Param("activeStatuses") Collection<AnalysisJobStatus> activeStatuses);

	/**
	 * 제출의 최신 분석 실행 1건. 정렬은 {@code assessment_round_attendance} View가 쓰는 것과 같다 —
	 * {@code execution_no DESC, started_at DESC NULLS LAST, job_id DESC}.
	 *
	 * <p>QUEUED는 {@code started_at}이 NULL이라 방금 만들어진 재시도 job이 실행 중인 이전 job보다 뒤로 밀리면
	 * 안 되므로 {@code execution_no}가 1순위다.
	 */
	Optional<AnalysisJob> findFirstBySubmissionIdOrderByExecutionNoDescStartedAtDescJobIdDesc(UUID submissionId);

	/** 폴링 대상. uq_analysis_job_active 가 batch_key+job_type 당 활성 1건을 보장한다. */
	List<AnalysisJob> findByStatusIn(Collection<AnalysisJobStatus> statuses);

	// external_job_id를 UPDATE하는 쿼리는 두지 않는다. 정상 접수 건은 202 응답의 ID를 채운 뒤
	// 최초 INSERT 한 번으로 저장하므로 기록해야 할 UPDATE가 없고, 통로를 만들어 두면 언젠가
	// 그 통로로 NULL이 들어간다 — 2026-08-13 사고가 정확히 그 모양이었다.
}
