package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisProgress;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.AnalysisRequest;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisDispatchRepository.DispatchTarget;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisModelRepository.AnalysisModel;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAiUsageRecorder;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAssessmentSessionPreparer;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 서버 없이 배치 로직을 검증한다. 서버가 내려가 있어도 상태 전이·404 보정은 확인할 수 있어야 한다.
 */
class AnalysisBatchServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-07T10:00:00Z");

	private static final String MODEL_CODE = "nvidia/nemotron-3-ultra-550b-a55b";
	private static final String PROVIDER_MODEL_CODE = "nemotron-3-ultra-550b-a55b";
	private static final UUID MODEL_ID = UUID.randomUUID();
	private static final int MAX_ATTEMPTS = 3;

	private AnalysisDispatchRepository dispatchRepository;
	private AnalysisJobRepository jobRepository;
	private AnalysisModelRepository modelRepository;
	private JdbcAnalysisResultRepository resultRepository;
	private JdbcAiUsageRecorder usageRecorder;
	private JdbcAssessmentSessionPreparer sessionPreparer;
	private AnalysisServerClient client;
	private AnalysisBatchService service;

	@BeforeEach
	void setUp() {
		dispatchRepository = mock(AnalysisDispatchRepository.class);
		jobRepository = mock(AnalysisJobRepository.class);
		modelRepository = mock(AnalysisModelRepository.class);
		resultRepository = mock(JdbcAnalysisResultRepository.class);
		usageRecorder = mock(JdbcAiUsageRecorder.class);
		sessionPreparer = mock(JdbcAssessmentSessionPreparer.class);
		client = mock(AnalysisServerClient.class);
		service = new AnalysisBatchService(dispatchRepository, jobRepository, modelRepository,
				resultRepository, usageRecorder, sessionPreparer, client, MODEL_CODE, MAX_ATTEMPTS);
	}

	/** 카탈로그에 설정된 모델이 있는 정상 상태. */
	private void catalogHasTheConfiguredModel() {
		AnalysisModel model = mock(AnalysisModel.class);
		when(model.getModelId()).thenReturn(MODEL_ID);
		when(model.getProviderModelCode()).thenReturn(PROVIDER_MODEL_CODE);
		when(modelRepository.findActiveByModelCode(MODEL_CODE)).thenReturn(Optional.of(model));
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
				AnalysisJobStatus.FAILED, NOW, NOW, AnalysisFailureCode.REPO_NOT_FOUND, "저장소 없음", null, null)));

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
				AnalysisJobStatus.FAILED, NOW, NOW, null, null, null, null)));

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
				AnalysisJobStatus.QUEUED, null, null, null, null, null, null)));

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

	private DispatchTarget target() {
		DispatchTarget target = mock(DispatchTarget.class);
		when(target.getSubmissionId()).thenReturn(UUID.randomUUID());
		when(target.getOrgId()).thenReturn(UUID.randomUUID());
		when(target.getTeamId()).thenReturn(UUID.randomUUID());
		when(target.getAssessmentRoundId()).thenReturn(UUID.randomUUID());
		when(target.getMethod()).thenReturn("GITHUB_URL");
		when(target.getRequestedBranch()).thenReturn("main");
		when(target.getRepositoryUrl()).thenReturn("https://github.com/team-iz/mini-project-3");
		return target;
	}

	@Test
	void dispatchSubmissionSendsTheRepositoryUrlAndBranchFromTheTarget() {
		DispatchTarget target = target();
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDispatchTarget(target.getSubmissionId(), MAX_ATTEMPTS)).thenReturn(Optional.of(target));
		when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(client.requestAnalysis(any())).thenReturn(UUID.randomUUID());

		service.dispatchSubmission(target.getSubmissionId());

		ArgumentCaptor<AnalysisRequest> captor = ArgumentCaptor.forClass(AnalysisRequest.class);
		verify(client).requestAnalysis(captor.capture());
		AnalysisRequest sent = captor.getValue();
		assertThat(sent.method()).isEqualTo("GITHUB_URL");
		assertThat(sent.submissionId()).isEqualTo(target.getSubmissionId());
		assertThat(sent.repositoryUrl()).isEqualTo(target.getRepositoryUrl());
		assertThat(sent.requestedBranch()).isEqualTo("main");
		// AI 서버 계약: submissionId:attemptNo. attemptNo는 execution_no(첫 실행은 1)다.
		assertThat(sent.idempotencyKey()).isEqualTo(target.getSubmissionId() + ":1");

		ArgumentCaptor<AnalysisJob> jobCaptor = ArgumentCaptor.forClass(AnalysisJob.class);
		verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
		assertThat(jobCaptor.getValue().getExternalJobId()).isNotNull();
	}

	@Test
	void dispatchSubmissionAlwaysNamesTheModelRatherThanLettingTheServerPick() {
		DispatchTarget target = target();
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDispatchTarget(target.getSubmissionId(), MAX_ATTEMPTS)).thenReturn(Optional.of(target));
		when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(client.requestAnalysis(any())).thenReturn(UUID.randomUUID());

		service.dispatchSubmission(target.getSubmissionId());

		ArgumentCaptor<AnalysisRequest> captor = ArgumentCaptor.forClass(AnalysisRequest.class);
		verify(client).requestAnalysis(captor.capture());
		// 생략하면 AI가 자기 기본 모델을 쓰고, 그 modelCode 가 ai_model 에 없으면
		// ai_usage.model_code FK 위반으로 사용량이 통째로 유실된다.
		assertThat(captor.getValue().providerModelCode()).isEqualTo(PROVIDER_MODEL_CODE);
		// 화면 선택값(model_code)이 아니라 공급자 원본 식별자를 보내야 호출이 된다.
		assertThat(captor.getValue().providerModelCode()).doesNotContain("/");

		ArgumentCaptor<AnalysisJob> jobCaptor = ArgumentCaptor.forClass(AnalysisJob.class);
		verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
		assertThat(jobCaptor.getValue().getRequestedModelId()).isEqualTo(MODEL_ID);
	}

	@Test
	void dispatchSubmissionLeavesNoJobRowWhenTheConfiguredModelIsMissing() {
		DispatchTarget target = target();
		when(dispatchRepository.findDispatchTarget(target.getSubmissionId(), MAX_ATTEMPTS)).thenReturn(Optional.of(target));
		when(modelRepository.findActiveByModelCode(MODEL_CODE)).thenReturn(Optional.empty());

		service.dispatchSubmission(target.getSubmissionId());

		// 요청을 보내지 않는다 -- 모델을 생략한 채로 보내면 사용량 적재가 깨진다.
		verify(client, never()).requestAnalysis(any());
		// job 행도 남기지 않는다. 재시도 불가 실패로 남으면 그 제출은 두 번 다시 집히지 않는다.
		verify(jobRepository, never()).save(any());
	}

	@Test
	void retryUsesAFreshExecutionNoSoTheIdempotencyKeyDiffersFromTheFailedAttempt() {
		DispatchTarget target = target();
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findRetryableSubmissions(MAX_ATTEMPTS)).thenReturn(List.of(target));
		// 앞선 시도 1회가 일시적 실패로 끝나 있다.
		when(dispatchRepository.findMaxExecutionNo(target.getSubmissionId())).thenReturn(1);
		when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(client.requestAnalysis(any())).thenReturn(UUID.randomUUID());

		assertThat(service.retryPendingSubmissions()).isEqualTo(1);

		ArgumentCaptor<AnalysisRequest> captor = ArgumentCaptor.forClass(AnalysisRequest.class);
		verify(client).requestAnalysis(captor.capture());
		// execution_no 를 1로 고정하면 AI 가 앞선 실패 요청과 같은 멱등키로 보고 중복 판정할 수 있어
		// 재시도가 요청조차 되지 않는다.
		assertThat(captor.getValue().idempotencyKey()).isEqualTo(target.getSubmissionId() + ":2");

		ArgumentCaptor<AnalysisJob> jobCaptor = ArgumentCaptor.forClass(AnalysisJob.class);
		verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
		assertThat(jobCaptor.getValue().getExecutionNo()).isEqualTo(2);
	}

	@Test
	void firstAttemptStartsAtExecutionNoOne() {
		DispatchTarget target = target();
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findRetryableSubmissions(MAX_ATTEMPTS)).thenReturn(List.of(target));
		// 행이 없으면 max() 가 NULL 이다.
		when(dispatchRepository.findMaxExecutionNo(target.getSubmissionId())).thenReturn(null);
		when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(client.requestAnalysis(any())).thenReturn(UUID.randomUUID());

		service.retryPendingSubmissions();

		ArgumentCaptor<AnalysisJob> jobCaptor = ArgumentCaptor.forClass(AnalysisJob.class);
		verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
		// ck_analysis_job_execution_no 가 0 이하를 막는다.
		assertThat(jobCaptor.getValue().getExecutionNo()).isEqualTo(1);
	}

	@Test
	void retryPassesTheAttemptLimitToTheQueryRatherThanFilteringInMemory() {
		when(dispatchRepository.findRetryableSubmissions(MAX_ATTEMPTS)).thenReturn(List.of());

		assertThat(service.retryPendingSubmissions()).isZero();

		// 상한 판정은 SQL 이 한다. 여기서 거른다면 상한을 넘긴 제출을 매번 읽어 오게 된다.
		verify(dispatchRepository).findRetryableSubmissions(MAX_ATTEMPTS);
		verify(client, never()).requestAnalysis(any());
	}

	@Test
	void dispatchSubmissionDoesNothingWhenTheSubmissionIsNotADispatchTarget() {
		UUID submissionId = UUID.randomUUID();
		// 이미 처리됐거나(job 존재) 슈퍼시드된 경우다 — findDispatchTarget 문서 참조.
		when(dispatchRepository.findDispatchTarget(submissionId, MAX_ATTEMPTS)).thenReturn(Optional.empty());

		service.dispatchSubmission(submissionId);

		verify(client, never()).requestAnalysis(any());
		verify(jobRepository, never()).save(any());
	}

	@Test
	void dispatchSubmissionSwallowsAServerFailureRatherThanPropagatingIt() {
		DispatchTarget target = target();
		catalogHasTheConfiguredModel();
		when(dispatchRepository.findDispatchTarget(target.getSubmissionId(), MAX_ATTEMPTS)).thenReturn(Optional.of(target));
		when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(client.requestAnalysis(any())).thenThrow(
				new AnalysisServerException(AnalysisFailureCode.TEMPORARY_ERROR, "AI 서버 연결 실패"));

		// 비동기 리스너에서 예외가 나면 호출자(이미 응답이 나간 제출 요청)는 아무도 못 받는다.
		// 실패는 job 이 FAILED 로 기록하고, 메서드 자체는 조용히 끝나야 한다.
		service.dispatchSubmission(target.getSubmissionId());

		ArgumentCaptor<AnalysisJob> jobCaptor = ArgumentCaptor.forClass(AnalysisJob.class);
		verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(jobCaptor.capture());
		assertThat(jobCaptor.getValue().getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
		assertThat(jobCaptor.getValue().getFailureCode()).isEqualTo(AnalysisFailureCode.TEMPORARY_ERROR);
	}
}
