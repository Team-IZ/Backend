package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerException;
import com.bigproject.backend.domain.submission.application.SubmissionArtifactStorage;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3(2026-08-13): AI 앱이 명시한 {@code JOB_NOT_FOUND}만 "job이 사라졌다"는 확정 신호로 다룬다.
 * 본문 없는 프록시/envoy 404는 그 신호가 아니므로 예외로 던져 폴링이 판단을 미루게 한다.
 */
class HttpAnalysisServerClientTest {

	private AiClient aiClient;
	private HttpAnalysisServerClient client;

	@BeforeEach
	void setUp() {
		aiClient = mock(AiClient.class);
		client = new HttpAnalysisServerClient(aiClient, mock(SubmissionArtifactStorage.class));
	}

	private void aiRespondsWith(AiCallException exception) {
		when(aiClient.get(anyString(), any(), isNull())).thenThrow(exception);
	}

	@Test
	void treatsAisOwnJobNotFoundAsConfirmedGone() {
		aiRespondsWith(new AiCallException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", false,
				"분석 job을 찾을 수 없습니다"));

		assertThat(client.fetchProgress(UUID.randomUUID())).isEmpty();
	}

	@Test
	void doesNotTreatABodylessProxy404AsConfirmedGone() {
		// AiClient.translate()가 JSON이 아닌 본문을 못 읽으면 failureCode가 null로 남는다 —
		// envoy/프록시가 낸 404가 이 모양이다.
		aiRespondsWith(new AiCallException(HttpStatus.NOT_FOUND, null, false, "Not Found"));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
	}

	@Test
	void doesNotTreatA404WithADifferentFailureCodeAsConfirmedGone() {
		// 정확히 "JOB_NOT_FOUND"일 때만 확정한다 — 값 집합 밖의 다른 코드는 모호한 신호로 다룬다.
		aiRespondsWith(new AiCallException(HttpStatus.NOT_FOUND, "SOME_OTHER_CODE", false, "..."));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
	}

	@Test
	void stillThrowsForNon404Failures() {
		aiRespondsWith(new AiCallException(HttpStatus.INTERNAL_SERVER_ERROR, "PROVIDER_ERROR", true, "..."));

		assertThatThrownBy(() -> client.fetchProgress(UUID.randomUUID()))
				.isInstanceOf(AnalysisServerException.class);
	}
}
