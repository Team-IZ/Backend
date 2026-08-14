package com.bigproject.backend.global.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI(FastAPI) 호출용 {@link RestClient}·{@link AiClient} 빈.
 *
 * <h2>대상 서버가 둘이다 (2026-08-11)</h2>
 *
 * <pre>
 * ai.proxy-base-url  : 리포트 생성, 세션 채점 — 그 외 전부
 * ai.origin-base-url : 교안 제출, 코드 제출(GitHub·ZIP) 분석
 * </pre>
 *
 * <p>두 값 모두 <b>호스트만</b> 담고 {@code /api/v0}를 포함하지 않는다. 프리픽스는
 * {@link AiClient#API_V0}로 경로 쪽에 있다 — 환경변수가 둘로 늘어난 이상, 프리픽스를 값에
 * 넣는 규약은 한쪽만 빠뜨리는 사고를 부른다.
 *
 * <h2>왜 새 의존성을 추가하지 않는가</h2>
 *
 * <p>{@code RestClient}는 {@code spring-boot-starter-webmvc}에 이미 들어 있는
 * {@code spring-web}의 클래스다. WebFlux를 끌어오면 리액터 스택이 통째로 붙는데
 * 이 프로젝트는 동기 MVC 서버라 얻는 것이 없다.
 *
 * <h2>타임아웃을 길게 잡는 이유</h2>
 *
 * <p>AI 저장소 {@code app/llm/client.py}가 LLM 호출 1회에 {@code SESSION_TIMEOUT_S = 20.0}초,
 * 재시도 {@code SESSION_MAX_ATTEMPTS = 6}회다. 즉 <b>최악의 경우 2분 가까이</b> 응답이 오지
 * 않는다. read timeout을 그보다 짧게 잡으면 AI는 정상적으로 작업 중인데 백엔드만 먼저 끊고
 * 재시도하게 되고, 그러면 <b>같은 요청에 LLM 비용이 두 번</b> 발생한다.
 *
 * <p>⚠️ 그 대가로 톰캣 스레드가 그동안 잡힌다. 리포트 생성처럼 동기로 오래 걸리는 호출을
 * 사용자 요청 스레드에서 직접 부르면 안 되고, 비동기 작업으로 빼야 한다
 * (AI의 {@code POST /reports}가 202 + job 폴링인 것도 같은 이유다).
 */
@Configuration
public class AiClientConfig {

	/** 프록시로 나가는 {@code RestClient}. 다른 {@code RestClient} 빈이 생겨도 섞이지 않게 한정한다. */
	public static final String AI_PROXY_REST_CLIENT = "aiProxyRestClient";

	/** 원본 서버로 나가는 {@code RestClient}. */
	public static final String AI_ORIGIN_REST_CLIENT = "aiOriginRestClient";

	/** 리포트·세션 채점 등 프록시 대상 호출이 주입받는 {@link AiClient}. */
	public static final String AI_PROXY_CLIENT = "aiProxyClient";

	/** 교안·코드 제출 분석이 주입받는 {@link AiClient}. */
	public static final String AI_ORIGIN_CLIENT = "aiOriginClient";

	@Bean(AI_PROXY_REST_CLIENT)
	public RestClient aiProxyRestClient(
			@Value("${ai.proxy-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.internal-key:}") String internalKey,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.read-timeout:PT150S}") Duration readTimeout,
			@Value("${ai.log-payloads:true}") boolean logPayloads
	) {
		return build(baseUrl, internalKey, connectTimeout, readTimeout, logPayloads);
	}

	@Bean(AI_ORIGIN_REST_CLIENT)
	public RestClient aiOriginRestClient(
			@Value("${ai.origin-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.internal-key:}") String internalKey,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.read-timeout:PT150S}") Duration readTimeout,
			@Value("${ai.log-payloads:true}") boolean logPayloads
	) {
		return build(baseUrl, internalKey, connectTimeout, readTimeout, logPayloads);
	}

	@Bean(AI_PROXY_CLIENT)
	public AiClient aiProxyClient(@Qualifier(AI_PROXY_REST_CLIENT) RestClient restClient) {
		return new AiClient(restClient);
	}

	@Bean(AI_ORIGIN_CLIENT)
	public AiClient aiOriginClient(@Qualifier(AI_ORIGIN_REST_CLIENT) RestClient restClient) {
		return new AiClient(restClient);
	}

	/**
	 * @param logPayloads AI와 주고받은 원문을 로그로 남길지({@link AiPayloadLoggingInterceptor}).
	 *                    켜면 응답 본문을 메모리에 버퍼링한다 — 인터셉터가 본문을 읽고 나서도
	 *                    메시지 컨버터가 다시 읽어야 하기 때문이다. 분석 결과처럼 큰 응답이
	 *                    부담되면 {@code ai.log-payloads=false}로 끈다.
	 */
	private static RestClient build(String baseUrl, String internalKey,
			Duration connectTimeout, Duration readTimeout, boolean logPayloads) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);

		RestClient.Builder builder = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(logPayloads ? new BufferingClientHttpRequestFactory(factory) : factory);

		if (logPayloads) {
			builder = builder.requestInterceptor(new AiPayloadLoggingInterceptor());
		}

		// 키가 비면 헤더 자체를 붙이지 않는다. AI 쪽 require_internal_key가 "키 미설정 = 로컬 개발"로
		// 검증을 건너뛰므로, 빈 문자열을 보내면 오히려 운영 설정 누락이 로컬처럼 조용히 통과한다.
		if (!internalKey.isBlank()) {
			builder = builder.defaultHeader(AiClient.INTERNAL_KEY_HEADER, internalKey);
		}

		return builder.build();
	}
}
