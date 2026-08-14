package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

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

	/**
	 * external_job_id를 쓰는 유일한 통로. {@link AnalysisJob#getExternalJobId()}가
	 * {@code updatable = false}라 save()/merge()의 자동생성 UPDATE는 이 컬럼을 절대 건드리지
	 * 않는다 — 그래서 실제 쓰기는 이 전용 쿼리로만 한다(D1, 2026-08-13 사고 재발 방지).
	 *
	 * <p>호출부(AnalysisBatchService)가 {@code @Transactional}을 쓰지 않으므로, 이 메서드를
	 * 부를 때는 반드시 {@code TransactionTemplate}으로 감싸야 한다({@code @Modifying} 쿼리는
	 * 활성 트랜잭션 없이 부르면 {@code TransactionRequiredException}이 난다).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = "UPDATE analysis_job SET external_job_id = :externalJobId WHERE job_id = :jobId",
			nativeQuery = true)
	int assignExternalJobId(@Param("jobId") UUID jobId, @Param("externalJobId") UUID externalJobId);
}
