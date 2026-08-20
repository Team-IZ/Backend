package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code requestAnalysis}/{@code checkStatus}가 예전엔 자체 {@code RestClient}를 직접 불러
 * 연결 실패·타임아웃·AI 4xx·5xx가 코드 없는 500으로 그대로 샜다(2026-08-20 재현, POST
 * /curricula/{materialId}/analyses). 공용 {@link AiClient}로 옮긴 뒤에는 그 실패들이
 * {@link AiCallException} 하나로 접혀 오고, 이 클래스가 그걸 {@code CURRICULUM_AI_UNAVAILABLE}로
 * 옮긴다는 것을 못 박는다.
 */
class AiCurriculumClientTest {

	private final AiProxyWarmUp proxyWarmUp = mock(AiProxyWarmUp.class);
	private final AiClient aiOriginClient = mock(AiClient.class);
	private final AiCurriculumClient client = new AiCurriculumClient(proxyWarmUp, aiOriginClient);

	@Test
	void translatesAiCallFailureOnRequestAnalysisToCurriculumAiUnavailable() {
		when(proxyWarmUp.warmUp()).thenReturn(true);
		when(aiOriginClient.postMultipart(anyString(), any(), any(), anyString(), any()))
				.thenThrow(new AiCallException(null, "TIMEOUT", true, "AI 서버에 닿지 못했습니다"));

		assertThatThrownBy(() -> client.requestAnalysis(
				UUID.randomUUID(), "Spring", new byte[]{1, 2, 3}, "key"))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						org.assertj.core.api.Assertions.assertThat(exception.errorCode())
								.isEqualTo(CurriculumErrorCode.CURRICULUM_AI_UNAVAILABLE));
	}

	@Test
	void translatesAiCallFailureOnCheckStatusToCurriculumAiUnavailable() {
		when(aiOriginClient.get(anyString(), any(), any()))
				.thenThrow(new AiCallException(null, "TIMEOUT", true, "AI 서버에 닿지 못했습니다"));

		assertThatThrownBy(() -> client.checkStatus(UUID.randomUUID().toString()))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						org.assertj.core.api.Assertions.assertThat(exception.errorCode())
								.isEqualTo(CurriculumErrorCode.CURRICULUM_AI_UNAVAILABLE));
	}

	@Test
	void doesNotCallAiWhenWarmUpFails() {
		when(proxyWarmUp.warmUp()).thenReturn(false);

		assertThatThrownBy(() -> client.requestAnalysis(
				UUID.randomUUID(), "Spring", new byte[]{1, 2, 3}, "key"))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						org.assertj.core.api.Assertions.assertThat(exception.errorCode())
								.isEqualTo(CurriculumErrorCode.CURRICULUM_AI_UNAVAILABLE));

		org.mockito.Mockito.verifyNoInteractions(aiOriginClient);
	}
}
