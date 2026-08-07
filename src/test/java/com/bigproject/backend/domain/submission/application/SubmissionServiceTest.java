package com.bigproject.backend.domain.submission.application;

import com.bigproject.backend.domain.codeanalysis.infrastructure.AnalysisJobRepository;
import com.bigproject.backend.domain.submission.domain.Repository;
import com.bigproject.backend.domain.submission.domain.RepositoryStatus;
import com.bigproject.backend.domain.submission.domain.Submission;
import com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent;
import com.bigproject.backend.domain.submission.infrastructure.GithubRepositoryRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionArtifactRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionContextRepository.SubmissionContext;
import com.bigproject.backend.domain.submission.infrastructure.SubmissionRepository;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
	private ApplicationEventPublisher eventPublisher;
	private SubmissionService service;

	@BeforeEach
	void setUp() {
		submissionRepository = mock(SubmissionRepository.class);
		githubRepositoryRepository = mock(GithubRepositoryRepository.class);
		SubmissionArtifactRepository submissionArtifactRepository = mock(SubmissionArtifactRepository.class);
		submissionContextRepository = mock(SubmissionContextRepository.class);
		AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
		SubmissionArtifactStorage artifactStorage = mock(SubmissionArtifactStorage.class);
		eventPublisher = mock(ApplicationEventPublisher.class);

		service = new SubmissionService(submissionRepository, githubRepositoryRepository,
				submissionArtifactRepository, submissionContextRepository, analysisJobRepository,
				artifactStorage, eventPublisher);
		ReflectionTestUtils.setField(service, "maxZipBytes", 52428800L);
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
}
