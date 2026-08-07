package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisProgress;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 서버 없이 배치 로직을 검증한다. 서버가 내려가 있어도 상태 전이·404 보정은 확인할 수 있어야 한다.
 */
class AnalysisBatchServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-07T10:00:00Z");

	private AnalysisDispatchRepository dispatchRepository;
	private AnalysisJobRepository jobRepository;
	private AnalysisServerClient client;
	private AnalysisBatchService service;

	@BeforeEach
	void setUp() {
		dispatchRepository = mock(AnalysisDispatchRepository.class);
		jobRepository = mock(AnalysisJobRepository.class);
		client = mock(AnalysisServerClient.class);
		service = new AnalysisBatchService(dispatchRepository, jobRepository, client);
	}

	private AnalysisJob jobWithExternalId() {
		AnalysisJob job = AnalysisJob.queued(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "batch-key", "CODE_ANALYSIS", 1, "trace-1");
		job.acceptExternalJob(UUID.randomUUID());
		return job;
	}

	@Test
	void clearsTheExternalJobIdWhenTheServerNoLongerKnowsIt() {
		AnalysisJob job = jobWithExternalId();
		when(jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
		// AI 스펙: "job storage is in-memory; process restart causes 404" — 정상 동작 중에도 난다.
		when(client.fetchProgress(any())).thenReturn(Optional.empty());

		int updated = service.pollActiveJobs();

		assertThat(updated).isEqualTo(1);
		// 실패로 기록하지 않는다. 분석이 실패한 게 아니라 요청이 유실된 것이라 다시 보내야 한다.
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
		assertThat(job.getExternalJobId()).isNull();
		assertThat(job.getFailureCode()).isNull();
	}

	@Test
	void recordsFailureWithTheCodeTheServerSent() {
		AnalysisJob job = jobWithExternalId();
		when(jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
		when(client.fetchProgress(any())).thenReturn(Optional.of(new AnalysisProgress(
				AnalysisJobStatus.FAILED, NOW, NOW, AnalysisFailureCode.REPO_NOT_FOUND, "저장소 없음")));

		service.pollActiveJobs();

		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
		assertThat(job.getFailureCode()).isEqualTo(AnalysisFailureCode.REPO_NOT_FOUND);
		assertThat(job.getFailureReason()).isEqualTo("저장소 없음");
	}

	@Test
	void stillRecordsFailureWhenTheServerOmitsTheCode() {
		AnalysisJob job = jobWithExternalId();
		when(jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
		// 값 집합 밖의 코드는 AnalysisFailureCode.parse 가 비워서 넘긴다.
		when(client.fetchProgress(any())).thenReturn(Optional.of(new AnalysisProgress(
				AnalysisJobStatus.FAILED, NOW, NOW, null, null)));

		service.pollActiveJobs();

		// ck_analysis_job_failure_code 가 FAILED 에 코드를 요구한다. 코드가 없다고 실패를 통째로
		// 못 남기면 실패 이력이 비어버리므로 대체값으로라도 기록한다.
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
		assertThat(job.getFailureCode()).isEqualTo(AnalysisFailureCode.MODEL_ERROR);
		assertThat(job.getFailureReason()).isNotBlank();
	}

	@Test
	void leavesQueuedJobsUntouchedWhileTheServerHasNotStarted() {
		AnalysisJob job = jobWithExternalId();
		when(jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
		when(client.fetchProgress(any())).thenReturn(Optional.of(new AnalysisProgress(
				AnalysisJobStatus.QUEUED, null, null, null, null)));

		assertThat(service.pollActiveJobs()).isZero();
		assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
	}

	@Test
	void skipsJobsThatWereNeverAccepted() {
		AnalysisJob notSent = AnalysisJob.queued(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "batch-key", "CODE_ANALYSIS", 1, "trace-1");
		when(jobRepository.findByStatusIn(any())).thenReturn(List.of(notSent));

		// external_job_id 가 없으면 물어볼 대상이 없다. 다음 dispatch 가 다시 보낸다.
		assertThat(service.pollActiveJobs()).isZero();
	}
}
