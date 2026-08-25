package com.bigproject.backend.global.ai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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

	/** 세션 채점 전용 {@link RestClient}. 프록시와 같은 곳으로 나가되 상한이 짧다. */
	public static final String AI_SESSION_REST_CLIENT = "aiSessionRestClient";

	/** 답변 채점이 주입받는 {@link AiClient}. */
	public static final String AI_SESSION_CLIENT = "aiSessionClient";

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

	/**
	 * D-alb-cert(2026-08-26): 이 origin 클라이언트만 TLS 체인·호스트명 검증을 건너뛴다.
	 *
	 * <h2>WHY</h2>
	 * <p>{@code ai.origin-base-url}이 이제 ECS Fargate ALB(예:
	 * {@code teamiz-ai-alb-*.elb.amazonaws.com})를 직접 가리킨다. ALB의 기본 도메인은
	 * AWS 소유라 공인 인증서를 발급받을 수 없다(도메인 소유 증명 불가) — 우리가 소유한
	 * 커스텀 도메인(예: {@code ai.sayyou.ai})을 다시 붙이지 않기로 한 결정(2026-08-26)의
	 * 직접적인 대가다. App Runner였을 때는 자기 기본 도메인(*.awsapprunner.com)에
	 * AWS가 인증서를 자동으로 붙여줘서 이 문제 자체가 없었다.
	 *
	 * <h2>COST</h2>
	 * <p>이 경로(교안 제출, 코드 제출 분석)에 한해 TLS의 MITM 방어가 사실상 없다. 다른 두
	 * 클라이언트({@link #aiProxyRestClient}, {@link #aiSessionRestClient})는 그대로
	 * 검증한다 — 여기서만, ECS_ORIGIN이 우리 소유 AWS 계정 안의 ALB 하나로 고정돼 있다는
	 * 전제로 범위를 좁힌다(대상이 임의 호스트가 아니다).
	 *
	 * <h2>EXIT</h2>
	 * <p>커스텀 도메인을 다시 붙이고 그 도메인에 맞는 ACM 인증서를 ALB에 달면, 이 메서드를
	 * 지우고 {@link #build}로 되돌린다.
	 */
	@Bean(AI_ORIGIN_REST_CLIENT)
	public RestClient aiOriginRestClient(
			@Value("${ai.origin-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.internal-key:}") String internalKey,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.read-timeout:PT150S}") Duration readTimeout,
			@Value("${ai.log-payloads:true}") boolean logPayloads
	) {
		return buildTrustingAnyServer(baseUrl, internalKey, connectTimeout, readTimeout, logPayloads);
	}

	/**
	 * 세션 채점만 <b>다른 상한</b>을 쓴다. 나가는 곳은 프록시로 같다.
	 *
	 * <h2>왜 갈랐나</h2>
	 *
	 * <p>{@code ai.read-timeout}(150초)은 <b>사람이 기다리지 않는</b> 호출을 위한 값이다 — 코드 분석과
	 * 리포트 생성은 배치·비동기라 2분을 기다려도 잃는 것이 없다. 답변 채점만 학생이 화면 앞에서
	 * 기다리는데, 같은 값을 쓰면 <b>응답이 나갈 무렵에는 이미 연결이 끊겨 있다</b>(37차 R1 — 90초에
	 * 끊겼다). 그 뒤에 우리가 아무리 정확히 채점해도 학생에게 닿지 않는다.
	 *
	 * <p>그래서 짧게 잡는다. 짧게 잡아도 손해가 없는 이유는 <b>재전송이 공짜</b>이기 때문이다 —
	 * 멱등키가 {@code session:stage:axis:hintsUsed}로 결정론적이라({@code SessionAnswerGrader}) 같은
	 * 답을 다시 보내면 AI가 처음 응답을 그대로 돌려주고 LLM 비용이 늘지 않는다.
	 *
	 * <p>기본 55초는 <b>웜업이 요청 밖으로 빠진 것을 전제로 한</b> 값이다({@code AsyncAiProxyWarmUp}).
	 * 깨어 있는 서버의 채점은 4.5~7.7초라 55초는 여유가 크다. 이 값을 다시 늘려야 한다면 늘리기 전에
	 * "무엇이 55초를 넘겼는가"를 로그로 확인해야 한다 — 늘리는 것으로 해결되는 종류의 문제가 아니다.
	 */
	@Bean(AI_SESSION_REST_CLIENT)
	public RestClient aiSessionRestClient(
			@Value("${ai.proxy-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.internal-key:}") String internalKey,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.session.read-timeout:PT55S}") Duration readTimeout,
			@Value("${ai.log-payloads:true}") boolean logPayloads
	) {
		return build(baseUrl, internalKey, connectTimeout, readTimeout, logPayloads);
	}

	@Bean(AI_PROXY_CLIENT)
	public AiClient aiProxyClient(@Qualifier(AI_PROXY_REST_CLIENT) RestClient restClient) {
		return new AiClient(restClient);
	}

	@Bean(AI_SESSION_CLIENT)
	public AiClient aiSessionClient(@Qualifier(AI_SESSION_REST_CLIENT) RestClient restClient) {
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

	/**
	 * {@link #aiOriginRestClient} 전용. {@link #build}와 갈라진 이유는
	 * {@link #aiOriginRestClient}의 D-alb-cert 주석 참고 — 인증서 체인·호스트명 검증을
	 * 이 클라이언트에만 끄기 위해 {@code SimpleClientHttpRequestFactory}
	 * ({@code HttpURLConnection} 기반, 인스턴스별 SSLContext 지정 불가) 대신
	 * {@code JdkClientHttpRequestFactory}({@code java.net.http.HttpClient} 기반, 인스턴스별
	 * SSLContext 지정 가능)를 쓴다 — JVM 전역 기본 SSLContext는 안 건드리므로
	 * {@link #aiProxyRestClient}·{@link #aiSessionRestClient}는 영향받지 않는다.
	 */
	private static RestClient buildTrustingAnyServer(String baseUrl, String internalKey,
			Duration connectTimeout, Duration readTimeout, boolean logPayloads) {
		TrustManager trustAny = new X509ExtendedTrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {}
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {}
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};

		SSLContext sslContext;
		try {
			sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[]{trustAny}, new SecureRandom());
		}
		catch (GeneralSecurityException exception) {
			throw new IllegalStateException("AI origin용 trust-any SSLContext 구성 실패", exception);
		}

		// endpointIdentificationAlgorithm을 비워야 호스트명 검증(SNI/SAN 대조)까지 꺼진다 --
		// TrustManager만 바꾸면 체인 검증은 통과해도 호스트명 불일치(ALB 주소 vs 인증서 SAN)에서
		// 다시 막힌다.
		SSLParameters sslParameters = new SSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm(null);

		HttpClient httpClient = HttpClient.newBuilder()
				.sslContext(sslContext)
				.sslParameters(sslParameters)
				.connectTimeout(connectTimeout)
				.build();

		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);

		ClientHttpRequestFactory requestFactory = logPayloads
				? new BufferingClientHttpRequestFactory(factory)
				: factory;

		RestClient.Builder builder = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory);

		if (logPayloads) {
			builder = builder.requestInterceptor(new AiPayloadLoggingInterceptor());
		}
		if (!internalKey.isBlank()) {
			builder = builder.defaultHeader(AiClient.INTERNAL_KEY_HEADER, internalKey);
		}

		return builder.build();
	}
}
