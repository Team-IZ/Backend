package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4차 요청서 Q1이 실서버에서 관측한 증상 — <b>연속 실패 차단이 확률적으로만 걸린다</b> — 을
 * 재현하는 테스트다.
 *
 * <p>기존 {@code LoginAttemptThrottleTest}는 IP를 고정 문자열로 넘겨서 <b>이 증상을 잡을 수 없다.</b>
 * 실제 배포에서 달라지는 것은 임계값이나 만료 계산이 아니라 <b>매 요청 IP를 어떻게 얻느냐</b>였고,
 * 그 경로가 테스트에 전혀 들어와 있지 않았다. 그래서 여기서는 {@link ClientIpResolver}를 통과시켜
 * "프록시 뒤에서 온 요청"을 그대로 만든다.
 */
class LoginThrottleBehindProxyTest {
	private static final String EMAIL = "lead@example.com";
	private static final String CLIENT = "203.0.113.42";
	private static final int ATTEMPTS_BEFORE_BLOCK = 5;

	private final ClientIpResolver resolver = new ClientIpResolver("X-Forwarded-For", 1);

	/**
	 * <b>이것이 고쳐진 동작이다.</b> 프록시 주소가 요청마다 달라도 클라이언트가 같으면
	 * 다섯 번째 실패에서 카운터가 임계값에 닿는다.
	 */
	@Test
	void 프록시_주소가_요청마다_달라도_다섯_번_실패하면_막힌다() {
		LoginAttemptThrottle throttle = throttle();

		for (int attempt = 0; attempt < ATTEMPTS_BEFORE_BLOCK; attempt++) {
			throttle.recordFailure(EMAIL, resolver.resolve(requestThroughProxy(attempt, CLIENT)));
		}

		assertThatThrownBy(() -> throttle.checkNotBlocked(
				EMAIL,
				resolver.resolve(requestThroughProxy(99, CLIENT))
		)).isInstanceOf(ApiException.class);
	}

	/**
	 * <b>고치기 전 동작을 그대로 남겨 둔다.</b> 접속 주소를 그대로 키에 넣으면 다섯 번을 틀려도
	 * 카운터가 다섯 개로 흩어져 아무도 막히지 않는다 — 프론트가 본 "400이 섞여 나온다"가 이것이다.
	 * 이 테스트가 깨지는 날은 누군가 다시 {@code getRemoteAddr()}로 돌아간 날이다.
	 */
	@Test
	void 접속_주소를_그대로_키로_쓰면_다섯_번_실패해도_막히지_않는다() {
		LoginAttemptThrottle throttle = throttle();

		for (int attempt = 0; attempt < ATTEMPTS_BEFORE_BLOCK; attempt++) {
			throttle.recordFailure(EMAIL, requestThroughProxy(attempt, CLIENT).getRemoteAddr());
		}

		assertThatCode(() -> throttle.checkNotBlocked(
				EMAIL,
				requestThroughProxy(99, CLIENT).getRemoteAddr()
		)).doesNotThrowAnyException();
	}

	/**
	 * 차단된 상태에서 {@code X-Forwarded-For}를 바꿔 보내도 통과하지 못한다.
	 * 프록시가 마지막에 붙이는 값이 우리가 읽는 값이라 위조값은 왼쪽으로 밀린다.
	 */
	@Test
	void 헤더를_위조해도_차단을_피하지_못한다() {
		LoginAttemptThrottle throttle = throttle();
		for (int attempt = 0; attempt < ATTEMPTS_BEFORE_BLOCK; attempt++) {
			throttle.recordFailure(EMAIL, resolver.resolve(requestThroughProxy(attempt, CLIENT)));
		}

		MockHttpServletRequest spoofed = new MockHttpServletRequest();
		spoofed.setRemoteAddr("fd00::99");
		spoofed.addHeader("X-Forwarded-For", "198.51.100.9, " + CLIENT);

		assertThatThrownBy(() -> throttle.checkNotBlocked(EMAIL, resolver.resolve(spoofed)))
				.isInstanceOf(ApiException.class);
	}

	/** 다른 클라이언트는 여전히 영향을 받지 않는다 — 남을 막는 수단이 되지 않아야 한다. */
	@Test
	void 같은_프록시를_거쳐도_다른_클라이언트는_막히지_않는다() {
		LoginAttemptThrottle throttle = throttle();
		for (int attempt = 0; attempt < ATTEMPTS_BEFORE_BLOCK; attempt++) {
			throttle.recordFailure(EMAIL, resolver.resolve(requestThroughProxy(attempt, CLIENT)));
		}

		assertThatCode(() -> throttle.checkNotBlocked(
				EMAIL,
				resolver.resolve(requestThroughProxy(0, "198.51.100.7"))
		)).doesNotThrowAnyException();
	}

	/**
	 * IPv6 단말은 주소 뒷부분을 계속 바꾼다. 대역(/64)으로 묶지 않으면 IPv4에서 고친 것과
	 * 똑같은 이유로 다시 새기 시작한다.
	 */
	@Test
	void IPv6_임시_주소가_바뀌어도_같은_대역이면_한_카운터로_센다() {
		LoginAttemptThrottle throttle = throttle();
		String[] rotating = {
				"2001:db8:abcd:12::1",
				"2001:db8:abcd:12:1111:2222:3333:4444",
				"2001:db8:abcd:12:aaaa:bbbb:cccc:dddd",
				"2001:db8:abcd:12::9",
				"2001:db8:abcd:12:5555::1"
		};

		for (String address : rotating) {
			throttle.recordFailure(EMAIL, resolver.resolve(requestThroughProxy(0, address)));
		}

		assertThatThrownBy(() -> throttle.checkNotBlocked(EMAIL, "2001:db8:abcd:12::ff"))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * <b>남아 있는 한계를 코드로 못 박아 둔다.</b> 카운터는 프로세스 메모리에 있어서 인스턴스를
	 * 늘리면 인스턴스마다 따로 센다 — 지금 replica가 1개라 실제로 겪지 않을 뿐, 늘리는 순간
	 * Q1과 <b>똑같이 생긴 증상</b>이 원인만 바뀐 채 돌아온다. 그때는 IP 파싱이 아니라 공유
	 * 저장소가 답이므로, 이 테스트가 깨지는 것(= 공유되기 시작하는 것)이 곧 그 작업의 완료 신호다.
	 */
	@Test
	void 인스턴스가_둘이면_카운터를_공유하지_않는다_알려진_한계() {
		LoginAttemptThrottle first = throttle();
		LoginAttemptThrottle second = throttle();

		for (int attempt = 0; attempt < 3; attempt++) {
			first.recordFailure(EMAIL, CLIENT);
			second.recordFailure(EMAIL, CLIENT);
		}

		// 합쳐서 여섯 번을 틀렸는데도 어느 쪽도 임계값(5)에 닿지 못한다.
		assertThatCode(() -> first.checkNotBlocked(EMAIL, CLIENT)).doesNotThrowAnyException();
		assertThatCode(() -> second.checkNotBlocked(EMAIL, CLIENT)).doesNotThrowAnyException();
	}

	/** 감사 기록에 남는 IP도 프록시 주소가 아니라 진짜 클라이언트여야 한다. */
	@Test
	void 감사용_IP도_프록시_주소가_아니다() {
		assertThat(resolver.resolve(requestThroughProxy(0, CLIENT))).isNotEqualTo("fd00::100");
		assertThat(resolver.resolve(requestThroughProxy(0, CLIENT))).isEqualTo(CLIENT);
	}

	private LoginAttemptThrottle throttle() {
		return new LoginAttemptThrottle(ATTEMPTS_BEFORE_BLOCK, Duration.ofMinutes(1), Duration.ofMinutes(15));
	}

	/**
	 * Railway 프록시를 거쳐 들어온 요청. <b>접속 주소가 요청마다 다르다</b> — 프록시가 여러 대라
	 * 실제로 그렇게 보이고, 그것이 이 버그의 출발점이었다.
	 */
	private MockHttpServletRequest requestThroughProxy(int hop, String clientIp) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		// hop을 두 번째 그룹에 넣는다 — /64 접두(첫 4그룹)에 들어가야 접속 주소별로 차단 키가
		// 실제로 갈린다. 뒷그룹(인터페이스 식별자)에만 넣으면 IPv6 프라이버시-확장 대응으로 추가된
		// /64 묶음(ClientIpAddresses.throttleKey)에 의해 전부 같은 키로 뭉쳐 이 테스트의 전제가 깨진다.
		request.setRemoteAddr("fd00:" + (100 + hop) + "::1");
		request.addHeader("X-Forwarded-For", clientIp);
		return request;
	}
}
