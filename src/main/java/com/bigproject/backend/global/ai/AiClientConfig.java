package com.bigproject.backend.global.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI(FastAPI) 호출용 {@link RestClient} 빈.
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

	/** 스프링이 AI 서버로 나갈 때 쓰는 이름. 다른 {@code RestClient} 빈이 생겨도 섞이지 않게 한정한다. */
	public static final String AI_REST_CLIENT = "aiRestClient";

	@Bean(AI_REST_CLIENT)
	public RestClient aiRestClient(
			@Value("${ai.base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.internal-key:}") String internalKey,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.read-timeout:PT150S}") Duration readTimeout
	) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);

		RestClient.Builder builder = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory);

		// 키가 비면 헤더 자체를 붙이지 않는다. AI 쪽 require_internal_key가 "키 미설정 = 로컬 개발"로
		// 검증을 건너뛰므로, 빈 문자열을 보내면 오히려 운영 설정 누락이 로컬처럼 조용히 통과한다.
		if (!internalKey.isBlank()) {
			builder = builder.defaultHeader(AiClient.INTERNAL_KEY_HEADER, internalKey);
		}

		return builder.build();
	}
}
