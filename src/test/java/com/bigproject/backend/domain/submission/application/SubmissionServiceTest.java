package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.codeanalysis.infrastructure.JdbcAnalysisResultQueryRepository;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import com.bigproject.backend.domain.submission.domain.Repository;
import com.bigproject.backend.domain.submission.domain.RepositoryStatus;
import com.bigproject.backend.domain.submission.domain.Submission;
import com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.infrastructure.GithubRepositoryRepository;
import com.bigproject.backend.domain.submission.infrastructure.JdbcMeasurementAttemptOpener;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionArtifactRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository.SubmissionContext;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionRepository;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisPhase;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResponse;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import com.bigproject.backend.domain.submission.domain.SubmissionStatus;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-07에 추가된 {@link SubmissionAcceptedEvent} 발행 조건을 고정한다.
 *
 * <p>발행 자체보다 "언제 발행하지 않는가"가 더 중요하다 — 멱등 재생 응답에서 다시 발행하면 재시도
 * 한 번마다 분석이 다시 걸린다.
 */
class SubmissionServiceTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID ORG_ID = UUID.randomUUID();
	private static final UUID PROJECT_ID = UUID.randomUUID();
	private static final UUID TEAM_ID = UUID.randomUUID();
	private static final UUID ROUND_ID = UUID.randomUUID();

	private SubmissionRepository submissionRepository;
	private GithubRepositoryRepository githubRepositoryRepository;
	private SubmissionContextRepository submissionContextRepository;
	private SubmissionArtifactRepository submissionArtifactRepository;
	private SubmissionArtifactStorage artifactStorage;
	private ApplicationEventPublisher eventPublisher;
	private AiProxyWarmUp aiProxyWarmUp;
	private AnalysisJobRepository analysisJobRepository;
	private SubmissionService service;

	@BeforeEach
	void setUp() {
		submissionRepository = mock(SubmissionRepository.class);
		githubRepositoryRepository = mock(GithubRepositoryRepository.class);
		submissionArtifactRepository = mock(SubmissionArtifactRepository.class);
		submissionContextRepository = mock(SubmissionContextRepository.class);
		analysisJobRepository = mock(AnalysisJobRepository.class);
		artifactStorage = mock(SubmissionArtifactStorage.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		aiProxyWarmUp = mock(AiProxyWarmUp.class);
		when(aiProxyWarmUp.warmUp()).thenReturn(true);

		service = new SubmissionService(submissionRepository, githubRepositoryRepository,
				submissionArtifactRepository, submissionContextRepository, analysisJobRepository,
				artifactStorage, mock(JdbcAnalysisResultQueryRepository.class),
				mock(JdbcMeasurementAttemptOpener.class), eventPublisher, aiProxyWarmUp);
		ReflectionTestUtils.setField(service, "maxZipBytes", 52428800L);
	}

	@Test
	void reportsAnActiveAnalysisWhoseExternalJobIdIsMissingAsFailed() {
		UUID submissionId = UUID.randomUUID();
		Submission submission = Submission.acceptGithubUrl(ORG_ID, TEAM_ID, ROUND_ID, UUID.randomUUID(),
				"main", null, USER_ID, Instant.now(), UUID.randomUUID());
		ReflectionTestUtils.setField(submission, "submissionId", submissionId);
		SubmissionContext context = openRoundContext();
		AnalysisJob job = AnalysisJob.queued(ORG_ID, ROUND_ID, TEAM_ID, submissionId,
				"batch-key", "CODE_ANALYSIS", 1, "trace-id");
		ReflectionTestUtils.setField(job, "jobId", UUID.randomUUID());

		when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
		when(submissionContextRepository.findSubmissionContext(USER_ID, ROUND_ID))
				.thenReturn(Optional.of(context));
		when(analysisJobRepository.findFirstBySubmissionIdOrderByExecutionNoDescStartedAtDescJobIdDesc(submissionId))
				.thenReturn(Optional.of(job));

		SubmissionAnalysisResponse response = service.getAnalysis(USER_ID, submissionId);

		// 오류가 아니라 실패 응답이다. 목록(TR-02)이 이미 "분석 실패"로 보여 주는 상태와 맞춘다.
		assertThat(response.phase()).isEqualTo(SubmissionAnalysisPhase.FAILED);
		assertThat(response.failureCode()).isEqualTo(SubmissionAnalysisResponse.EXTERNAL_JOB_ID_LOST);
		assertThat(response.failureReason())
				.isEqualTo(SubmissionAnalysisResponse.EXTERNAL_JOB_ID_LOST_MESSAGE);
		assertThat(response.analysisJobId()).isEqualTo(job.getJobId());
		assertThat(response.codeAnalysisId()).isNull();
	}

	private SubmissionContext openRoundContext() {
		SubmissionContext context = mock(SubmissionContext.class);
		when(context.getOrgId()).thenReturn(ORG_ID);
		when(context.getProjectId()).thenReturn(PROJECT_ID);
		when(context.getTeamId()).thenReturn(TEAM_ID);
		when(context.getRoundStatus()).thenReturn("OPEN");
		when(context.getSubmissionDueAt()).thenReturn(Instant.now().plusSeconds(3600));
		when(context.getAllowGithubIntegration()).thenReturn(true);
		return context;
	}

	private CreateGithubSubmissionRequest request(UUID idempotencyKey) {
		return new CreateGithubSubmissionRequest(ROUND_ID, "https://github.com/team-iz/mini-project-3", "main");
	}

	@Test
	void publishesSubmissionAcceptedOnANewGithubSubmission() {
		SubmissionContext context = openRoundContext();
		when(submissionContextRepository.findSubmissionContext(USER_ID, ROUND_ID))
				.thenReturn(Optional.of(context));
		when(submissionRepository.findByRequestIdempotencyKey(any())).thenReturn(Optional.empty());
		when(submissionRepository.findByTeamIdAndAssessmentRoundIdAndCurrentIsTrue(TEAM_ID, ROUND_ID))
				.thenReturn(Optional.empty());
		when(githubRepositoryRepository.findByTeamIdAndStatus(TEAM_ID, RepositoryStatus.ACTIVE))
				.thenReturn(Optional.empty());
		when(githubRepositoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(submissionRepository.save(any())).thenAnswer(invocation -> {
			Submission submission = invocation.getArgument(0);
			ReflectionTestUtils.setField(submission, "submissionId", UUID.randomUUID());
			return submission;
		});

		service.submitGithubUrl(USER_ID, request(UUID.randomUUID()), UUID.randomUUID());

		ArgumentCaptor<SubmissionAcceptedEvent> captor = ArgumentCaptor.forClass(SubmissionAcceptedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().submissionId()).isNotNull();
	}

	@Test
	void doesNotRepublishOnAnIdempotentReplay() {
		UUID idempotencyKey = UUID.randomUUID();
		Submission existing = Submission.acceptGithubUrl(ORG_ID, TEAM_ID, ROUND_ID, UUID.randomUUID(),
				"main", null, USER_ID, Instant.now(), idempotencyKey);
		SubmissionContext context = openRoundContext();
		when(submissionContextRepository.findSubmissionContext(USER_ID, ROUND_ID))
				.thenReturn(Optional.of(context));
		when(submissionRepository.findByRequestIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

		service.submitGithubUrl(USER_ID, request(idempotencyKey), idempotencyKey);

		// 재시도로 같은 응답을 돌려줄 뿐 새 제출이 아니다. 다시 발행하면 재시도 한 번마다 분석이
		// 다시 걸린다.
		verify(eventPublisher, never()).publishEvent(any());
		verify(githubRepositoryRepository, never()).save(any());
	}

	@Test
	void publishesOnlyAfterTheRepositoryRowIsResolved() {
		SubmissionContext context = openRoundContext();
		when(submissionContextRepository.findSubmissionContext(USER_ID, ROUND_ID))
				.thenReturn(Optional.of(context));
		when(submissionRepository.findByRequestIdempotencyKey(any())).thenReturn(Optional.empty());
		when(submissionRepository.findByTeamIdAndAssessmentRoundIdAndCurrentIsTrue(TEAM_ID, ROUND_ID))
				.thenReturn(Optional.empty());
		Repository existingRepo = Repository.active(PROJECT_ID, TEAM_ID, ORG_ID,
				"https://github.com/team-iz/mini-project-3", "https://github.com/team-iz/mini-project-3",
				Instant.now());
		when(githubRepositoryRepository.findByTeamIdAndStatus(TEAM_ID, RepositoryStatus.ACTIVE))
				.thenReturn(Optional.of(existingRepo));
		when(submissionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.submitGithubUrl(USER_ID, request(UUID.randomUUID()), UUID.randomUUID());

		// 재제출로 팀 저장소 주소를 갱신하는 케이스다. 새 repository 행을 만들지는 않지만
		// 이벤트는 여전히 발행돼야 분석이 새 제출을 대상으로 다시 걸린다.
		verify(eventPublisher).publishEvent(any(SubmissionAcceptedEvent.class));
	}

	/** 접수 검증이 확장자가 아니라 실제 엔트리를 읽으므로 진짜 ZIP 이 필요하다. */
	private static byte[] zipWithOneEntry() {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("main.py"));
			zip.write("print('hi')".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (java.io.IOException exception) {
			throw new IllegalStateException(exception);
		}
		return buffer.toByteArray();
	}

	@Test
	void acceptsZipUploadsAndTriggersAnalysisLikeTheGithubPath() {
		SubmissionContext context = openRoundContext();
		when(context.getAllowZipSubmission()).thenReturn(true);
		when(submissionContextRepository.findSubmissionContext(USER_ID, ROUND_ID))
				.thenReturn(Optional.of(context));
		when(submissionRepository.findByRequestIdempotencyKey(any())).thenReturn(Optional.empty());
		when(submissionRepository.findByTeamIdAndAssessmentRoundIdAndCurrentIsTrue(TEAM_ID, ROUND_ID))
				.thenReturn(Optional.empty());
		when(submissionRepository.save(any())).thenAnswer(invocation -> {
			Submission submission = invocation.getArgument(0);
			ReflectionTestUtils.setField(submission, "submissionId", UUID.randomUUID());
			return submission;
		});
		when(artifactStorage.store(any(), any(), any(), any()))
				.thenReturn(new SubmissionArtifactStorage.StoredArtifact(
						"file:///var/submissions/x.zip", "a".repeat(64), 3L));
		when(submissionArtifactRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		SubmissionResponse response = service.submitZip(USER_ID, ROUND_ID,
				new MockMultipartFile("file", "project.zip", "application/zip", zipWithOneEntry()),
				UUID.randomUUID());

		// VALIDATING 으로 두면 분석 대상 조회(status='ACCEPTED')에 걸리지 않아 ZIP 은 영원히 분석되지 않는다.
		assertThat(response.status()).isEqualTo(SubmissionStatus.ACCEPTED);
		// 보류가 풀린 뒤로 ZIP 도 GitHub 과 같은 트리거를 쓴다.
		verify(eventPublisher).publishEvent(any(SubmissionAcceptedEvent.class));
	}

	/**
	 * AI 프록시를 깨우지 못하면 접수 자체를 막는다(2026-08-11).
	 *
	 * <p>제출 행이 남지 않는 것까지 확인하는 이유: 남으면 분석이 걸리지 않은 채 "제출됨"으로 보여
	 * 교육생이 재제출하지 않는다. 그 상태는 마감이 지나야 드러난다.
	 */
	@Test
	void rejectsGithubSubmissionWhenAiProxyCannotBeWokenUp() {
		when(aiProxyWarmUp.warmUp()).thenReturn(false);

		assertThatThrownBy(() -> service.submitGithubUrl(USER_ID, request(UUID.randomUUID()), UUID.randomUUID()))
				.isInstanceOf(SubmissionException.class)
				.hasFieldOrPropertyWithValue("errorCode", SubmissionErrorCode.AI_SERVER_UNAVAILABLE);

		verify(submissionRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any(SubmissionAcceptedEvent.class));
	}

	@Test
	void rejectsZipSubmissionWhenAiProxyCannotBeWokenUp() {
		when(aiProxyWarmUp.warmUp()).thenReturn(false);

		assertThatThrownBy(() -> service.submitZip(USER_ID, ROUND_ID,
				new MockMultipartFile("file", "project.zip", "application/zip", zipWithOneEntry()),
				UUID.randomUUID()))
				.isInstanceOf(SubmissionException.class)
				.hasFieldOrPropertyWithValue("errorCode", SubmissionErrorCode.AI_SERVER_UNAVAILABLE);

		// 업로드 저장까지 가면 안 된다. 접수하지 않을 파일을 S3에 남기는 셈이다.
		verify(artifactStorage, never()).store(any(), any(), any(), any());
		verify(submissionRepository, never()).save(any());
	}
}
