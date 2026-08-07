package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
