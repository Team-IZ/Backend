package com.bigproject.backend.global.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

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
	private final Duration awakeTtl;

	/**
	 * 마지막으로 깨어 있는 것을 확인한 시각. {@code volatile}이면 충분하다 — 두 스레드가 동시에
	 * 헬스체크를 한 번씩 더 보내도 손해가 없고, 잠금을 걸면 웨이크(80초)가 다른 요청을 막는다.
	 */
	private volatile Instant lastAwakeAt = Instant.EPOCH;

	public AiProxyWarmUp(
			@Value("${ai.proxy-base-url:http://localhost:8000}") String baseUrl,
			@Value("${ai.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${ai.proxy.warm-up-timeout:PT150S}") Duration warmUpTimeout,
			@Value("${ai.proxy.awake-ttl:PT5M}") Duration awakeTtl
	) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(warmUpTimeout);
		// /api/health는 X-Internal-Key 인증이 면제된다(AI README) — 키를 싣지 않는다.
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		this.awakeTtl = awakeTtl;
	}

	/**
	 * 최근에 깨어 있는 것을 확인했으면 아무것도 하지 않는다. 아니면 {@link #warmUp()}을 부른다.
	 *
	 * <p>깨우기 신호를 여러 곳에서 보내도 헬스체크가 그만큼 늘지 않게 하는 것이 목적이다 — 세션 시작과
	 * 첫 타이핑이 몇 초 간격으로 오면 두 번째는 건너뛴다.
	 *
	 * <p>⚠️ <b>요청 스레드에서 부르지 마라.</b> 캐시가 만료됐고 원본이 잠들어 있으면 여기서 80초를
	 * 기다린다. 이 메서드는 {@code AsyncAiProxyWarmUp}처럼 뒤에서 도는 자리를 위한 것이다.
	 */
	public boolean ensureAwake() {
		if (Instant.now().isBefore(lastAwakeAt.plus(awakeTtl))) {
			return true;
		}
		return warmUp();
	}

	/**
	 * 프록시를 깨운다. 깨어났으면 {@code true}.
	 *
	 * <p>예외를 밖으로 내보내지 않는다 — 호출부가 알아야 하는 것은 "원본으로 보내도 되는가" 하나뿐이고,
	 * 실패 원인(연결 거부·타임아웃·5xx)은 여기서 로그로 남긴다. 도메인마다 다른 에러 코드로 접어야
	 * 하는데 전송 계층 예외를 그대로 흘리면 그 분기가 도메인 밖으로 새어 500이 된다.
	 *
	 * <p><b>성공도 로그로 남긴다.</b> 이 호출은 실패가 아니라 <b>성공하는 데 걸린 시간</b>이 문제인
	 * 자리다 — 잠든 원본을 깨우는 첫 호출이 80초쯤 걸리고, 그 시간이 그대로 학생 요청의 대기 시간이
	 * 된다. 소요 시간을 남기지 않으면 "AI가 느리다"와 "웨이크가 느리다"를 구분할 근거가 없다.
	 */
	public boolean warmUp() {
		long startedNanos = System.nanoTime();
		try {
			restClient.get()
					.uri(HEALTH_PATH)
					.retrieve()
					.toBodilessEntity();
			lastAwakeAt = Instant.now();
			log.info("AI 프록시 웜업 완료: 소요={}ms", elapsedMillis(startedNanos));
			return true;
		} catch (Exception exception) {
			log.warn("AI 프록시 웜업 실패: 소요={}ms, 원인={}", elapsedMillis(startedNanos), exception.toString());
			return false;
		}
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}
