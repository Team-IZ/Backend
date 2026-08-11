package com.bigproject.backend.global.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI 프록시를 깨우고, 깨어났는지 알려준다({@code GET /api/health}).
 *
 * <h2>이것은 생존 확인이 아니라 기동 요청이다</h2>
 *
 * <p>프록시는 wake/sleep Lambda다. 평상시 원본(App Runner)이 PAUSED로 내려가 있고, 이 호출이
 * {@code ResumeService}를 돌려 RUNNING이 될 때까지 <b>동기로</b> 기다린다. 유휴 후 첫 호출은
 * 최대 80초가량 걸린 관측이 있다.
 *
 * <p>🔴 그래서 타임아웃을 짧게 잡으면 안 된다. 2초 같은 값을 쓰면 <b>정상적인 잠든 상태</b>가
 * 전부 실패로 읽혀, AI가 멀쩡한데도 제출·교안 업로드가 거부된다. 이 클래스의 read timeout이
 * 공용 {@code ai.read-timeout}만큼 긴 것은 실수가 아니다.
 *
 * <h2>원본을 직접 부르기 전에 반드시 거친다</h2>
 *
 * <p>원본 도메인은 PAUSED를 스스로 깨우지 못하고 404만 돌려준다. 큰 본문(PDF·ZIP)은 Lambda
 * Function URL의 6MB 동기 페이로드 상한 때문에 원본으로 직접 보내야 하므로, "프록시로 깨우고
 * 원본으로 보낸다"가 그 경로의 유일한 순서다.
 */
@Slf4j
@Component
public class AiProxyWarmUp {

	/** 버전 프리픽스 밖의 경로다. {@link AiClient#API_V0}를 붙이지 않는다. */
	private static final String HEALTH_PATH = "/api/health";

	private final RestClient restClient;

	public AiProxyWarmUp(
			@Value("${ai.proxy-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.proxy.warm-up-timeout:PT150S}") Duration warmUpTimeout
	) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(warmUpTimeout);
		// /api/health는 X-Internal-Key 인증이 면제된다(AI README) — 키를 싣지 않는다.
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	/**
	 * 프록시를 깨운다. 깨어났으면 {@code true}.
	 *
	 * <p>예외를 밖으로 내보내지 않는다 — 호출부가 알아야 하는 것은 "원본으로 보내도 되는가" 하나뿐이고,
	 * 실패 원인(연결 거부·타임아웃·5xx)은 여기서 로그로 남긴다. 도메인마다 다른 에러 코드로 접어야
	 * 하는데 전송 계층 예외를 그대로 흘리면 그 분기가 도메인 밖으로 새어 500이 된다.
	 */
	public boolean warmUp() {
		try {
			restClient.get()
					.uri(HEALTH_PATH)
					.retrieve()
					.toBodilessEntity();
			return true;
		} catch (Exception exception) {
			log.warn("AI 프록시 웜업 실패: {}", exception.toString());
			return false;
		}
	}
}
