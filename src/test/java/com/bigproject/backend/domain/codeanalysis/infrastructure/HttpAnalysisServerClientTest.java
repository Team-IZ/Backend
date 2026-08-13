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

	@Test
	void treatsOnlyTheAiApplicationsJobNotFoundAsAnUnknownJob() {
		when(aiClient.get(anyString(), any(), isNull())).thenThrow(
				new AiCallException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", false, "job 없음"));

		Optional<?> result = client.fetchProgress(UUID.randomUUID());

		assertThat(result).isEmpty();
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
		when(aiClient.get(anyString(), any(), isNull())).thenThrow(
				new AiCallException(HttpStatus.NOT_FOUND, null, false, "빈 프록시 응답"));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
	}
}
