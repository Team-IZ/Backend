package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationAiClient;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationJob;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationRequest;
import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;
import com.bigproject.backend.global.ai.AiCallException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 폴링 루프의 판단만 본다 — 언제 멈추고, 무엇을 던지고, 무엇을 돌려주는가.
 * 실제 HTTP는 {@link ReportGenerationAiClient}를 목으로 두어 빠진다.
 */
class ReportGenerationServiceImplTest {

	private static final UUID PROBLEM_ID = UUID.randomUUID();
	private static final UUID SNAPSHOT_ID = UUID.randomUUID();
	private static final String JOB_ID = "job-1";

	private final ReportGenerationAiClient aiClient = mock(ReportGenerationAiClient.class);

	/** 테스트에서는 재우지 않는다 — 폴링 60회가 60초가 되면 안 된다. */
	private final ReportGenerationService service =
			new ReportGenerationServiceImpl(aiClient, Duration.ZERO, 5, millis -> { });

	private final AiUsageAttribution attribution = AiUsageAttribution.userTriggered(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);

	@Test
	void 종료_상태가_되면_그_job을_돌려준다() {
		accepted();
		when(aiClient.fetchJob(eq(JOB_ID), any(), any(), any()))
				.thenReturn(job("RUNNING"))
				.thenReturn(job("SUCCEEDED"));

		ReportGenerationJob.Status result = service.generate(request(), attribution, SNAPSHOT_ID, "trace-1");

		assertThat(result.status()).isEqualTo("SUCCEEDED");
		verify(aiClient, times(2)).fetchJob(eq(JOB_ID), eq("trace-1"), any(), eq(SNAPSHOT_ID));
	}

	@Test
	void 실패한_job은_예외가_아니라_반환값이다() {
		accepted();
		when(aiClient.fetchJob(eq(JOB_ID), any(), any(), any())).thenReturn(job("FAILED"));

		ReportGenerationJob.Status result = service.generate(request(), attribution, SNAPSHOT_ID, null);

		// 실패도 저장해야 할 사실이다. 예외로 던지면 그 정보가 스택트레이스로만 남는다.
		assertThat(result.isFailed()).isTrue();
	}

	@Test
	void 부분_성공도_종료_상태로_본다() {
		accepted();
		when(aiClient.fetchJob(eq(JOB_ID), any(), any(), any())).thenReturn(job("PARTIAL"));

		ReportGenerationJob.Status result = service.generate(request(), attribution, SNAPSHOT_ID, null);

		assertThat(result.status()).isEqualTo("PARTIAL");
	}

	@Test
	void 제한_시간_안에_안_끝나면_재시도_가능한_TIMEOUT이다() {
		accepted();
		when(aiClient.fetchJob(eq(JOB_ID), any(), any(), any())).thenReturn(job("RUNNING"));

		assertThatThrownBy(() -> service.generate(request(), attribution, SNAPSHOT_ID, null))
				.isInstanceOf(AiCallException.class)
				.satisfies(thrown -> {
					AiCallException exception = (AiCallException) thrown;
					// AI 쪽 job은 아직 살아 있을 수 있다. 같은 멱등키로 다시 부르면
					// AI가 처음 jobId를 그대로 돌려주고 LLM을 다시 부르지 않는다.
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.failureCode()).isEqualTo("TIMEOUT");
				});

		verify(aiClient, times(5)).fetchJob(eq(JOB_ID), any(), any(), any());
	}

	@Test
	void jobId_없이_접수되면_폴링하지_않고_끊는다() {
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(null, "QUEUED"));

		assertThatThrownBy(() -> service.generate(request(), attribution, SNAPSHOT_ID, null))
				.isInstanceOf(AiCallException.class)
				.satisfies(thrown -> {
					// 계약 위반이지 일시적 장애가 아니라 다시 불러도 같다.
					assertThat(((AiCallException) thrown).retryable()).isFalse();
				});

		verify(aiClient, never()).fetchJob(any(), any(), any(), any());
	}

	@Test
	void 멱등키는_problemId와_scoreRunId를_잇는다() {
		// ai_usage.idempotency_key가 전역 UNIQUE다. problemId만 쓰면 재채점 때 충돌해
		// 두 번째 원장 행이 통째로 거부된다.
		assertThat(request().idempotencyKey()).isEqualTo(PROBLEM_ID + ":run-1");
	}

	private void accepted() {
		when(aiClient.requestGeneration(any(), any()))
				.thenReturn(new ReportGenerationJob.Accepted(JOB_ID, "QUEUED"));
	}

	private static ReportGenerationRequest request() {
		return new ReportGenerationRequest(
				PROBLEM_ID, 1, UUID.randomUUID(), "run-1", null, List.of(), List.of(), List.of());
	}

	private static ReportGenerationJob.Status job(String status) {
		return new ReportGenerationJob.Status(
				JOB_ID, PROBLEM_ID.toString(), null, status,
				"FAILED".equals(status) ? "모델 응답이 계약을 어겼습니다" : null,
				null, null, null, List.of());
	}
}
