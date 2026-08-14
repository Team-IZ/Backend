package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerException;
import com.bigproject.backend.domain.submission.application.SubmissionArtifactStorage;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 404를 어떻게 읽는가.
 *
 * <p>AI 앱이 명시한 {@code JOB_NOT_FOUND}만 "job이 사라졌다"는 신호로 다룬다. 그 밖의 404는
 * 원본이 PAUSED이거나 라우팅 계층이 준비되지 않아 앞단에서 난 것일 수 있으므로, 프록시를 깨워
 * 한 번 더 확인하고 그래도 안 되면 예외로 던져 이번 폴링만 건너뛰게 한다.
 */
class HttpAnalysisServerClientTest {

	private AiClient aiClient;
	private AiProxyWarmUp proxyWarmUp;
	private HttpAnalysisServerClient client;

	@BeforeEach
	void setUp() {
		aiClient = mock(AiClient.class);
		proxyWarmUp = mock(AiProxyWarmUp.class);
		client = new HttpAnalysisServerClient(
				aiClient, mock(SubmissionArtifactStorage.class), proxyWarmUp);
	}

	private void aiRespondsWith(AiCallException exception) {
		when(aiClient.get(anyString(), any(), isNull())).thenThrow(exception);
	}

	@Test
	void treatsOnlyTheAiApplicationsJobNotFoundAsAnUnknownJob() {
		aiRespondsWith(new AiCallException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", false, "job 없음"));

		Optional<?> result = client.fetchProgress(UUID.randomUUID());

		assertThat(result).isEmpty();
		// AI가 직접 답한 신호라 웜업으로 되물을 이유가 없다.
		verify(proxyWarmUp, never()).warmUp();
	}

	@Test
	void warmsUpAndRetriesAProxyLayer404() {
		when(proxyWarmUp.warmUp()).thenReturn(true);
		when(aiClient.get(anyString(), any(), isNull()))
				.thenThrow(new AiCallException(HttpStatus.NOT_FOUND, null, false, "envoy 404"))
				.thenThrow(new AiCallException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", false, "job 없음"));

		Optional<?> result = client.fetchProgress(UUID.randomUUID());

		assertThat(result).isEmpty();
		verify(proxyWarmUp).warmUp();
	}

	@Test
	void doesNotCountAProxy404WhenWarmUpFails() {
		when(proxyWarmUp.warmUp()).thenReturn(false);
		// AiClient.translate()가 JSON이 아닌 본문을 못 읽으면 failureCode가 null로 남는다 —
		// envoy/프록시가 낸 404가 이 모양이다.
		aiRespondsWith(new AiCallException(HttpStatus.NOT_FOUND, null, false, "빈 프록시 응답"));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
	}

	@Test
	void doesNotTreatA404WithADifferentFailureCodeAsAnUnknownJob() {
		// 정확히 "JOB_NOT_FOUND"일 때만 확정한다 — 값 집합 밖의 다른 코드는 모호한 신호로 다뤄
		// 웜업 경로로 넘긴다. 유실로 접었다면 멀쩡한 job을 죽였을 입력이다.
		when(proxyWarmUp.warmUp()).thenReturn(false);
		aiRespondsWith(new AiCallException(HttpStatus.NOT_FOUND, "SOME_OTHER_CODE", false, "..."));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
		verify(proxyWarmUp).warmUp();
	}

	@Test
	void stillThrowsForNon404Failures() {
		aiRespondsWith(new AiCallException(
				HttpStatus.INTERNAL_SERVER_ERROR, "PROVIDER_ERROR", true, "..."));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
		// 404가 아니면 웜업 대상이 아니다.
		verify(proxyWarmUp, never()).warmUp();
	}
}
