package com.bigproject.backend.global.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 코드 제출 접수 전에 AI 프록시가 살아 있는지 확인한다({@code GET /api/health}).
 *
 * <h2>왜 제출 시점에 확인하는가</h2>
 *
 * <p>분석 요청 자체는 제출 접수 후 {@code SubmissionAcceptedEvent}가 비동기로 보낸다. 그래서
 * 프록시가 죽어 있어도 제출은 성공으로 응답되고, 교육생은 한참 뒤 분석 화면에서야 실패를 본다.
 * 그 시점엔 재제출 마감이 지나 있을 수 있다. 접수 자체를 막으면 교육생이 즉시 알고 다시 시도한다.
 *
 * <h2>{@code /api/v0}가 붙지 않는다</h2>
 *
 * <p>헬스체크는 API 버전 프리픽스 밖의 경로다. {@link AiClient#API_V0}를 붙이면 404가 난다.
 *
 * <h2>공용 {@link AiClient}를 쓰지 않는 이유</h2>
 *
 * <p>🔴 그쪽 read timeout은 150초다(LLM 호출을 기다려야 해서 그렇다). 헬스체크에 그 값을 쓰면
 * 프록시가 응답 없이 멈춰 있을 때 제출 요청 하나가 2분 30초 동안 톰캣 스레드를 붙든다 —
 * "빨리 실패시켜 사용자에게 알린다"는 목적과 정반대다. 그래서 짧은 타임아웃의 클라이언트를
 * 따로 만든다.
 */
@Slf4j
@Component
public class AiProxyHealthChecker {

	/** 버전 프리픽스 밖의 경로다. {@link AiClient#API_V0}를 붙이지 않는다. */
	private static final String HEALTH_PATH = "/api/health";

	private final RestClient restClient;

	public AiProxyHealthChecker(
			@Value("${ai.proxy-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.internal-key:}") String internalKey,
			@Value("${ai.health.timeout:PT2S}") Duration timeout
	) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);

		RestClient.Builder builder = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory);
		if (!internalKey.isBlank()) {
			builder = builder.defaultHeader(AiClient.INTERNAL_KEY_HEADER, internalKey);
		}
		this.restClient = builder.build();
	}

	/**
	 * 프록시가 정상 응답하면 {@code true}.
	 *
	 * <p>예외를 밖으로 내보내지 않는다 — 호출부가 알아야 하는 것은 "제출을 받아도 되는가" 하나뿐이고,
	 * 실패 원인(연결 거부·타임아웃·5xx)은 여기서 로그로 남긴다.
	 */
	public boolean isHealthy() {
		try {
			restClient.get()
					.uri(HEALTH_PATH)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) -> {
						throw new IllegalStateException("status=" + response.getStatusCode());
					})
					.toBodilessEntity();
			return true;
		} catch (Exception exception) {
			log.warn("AI 프록시 헬스체크 실패: {}", exception.toString());
			return false;
		}
	}
}
