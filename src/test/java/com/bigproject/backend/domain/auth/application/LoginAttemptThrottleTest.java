package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptThrottleTest {
	private static final String EMAIL = "lead@example.com";
	private static final String IP = "203.0.113.1";

	private final LoginAttemptThrottle throttle = new LoginAttemptThrottle(
			5,
			Duration.ofMinutes(1),
			Duration.ofMinutes(15)
	);

	@Test
	void 임계값_전에는_통과시킨다() {
		for (int attempt = 0; attempt < 4; attempt++) {
			throttle.recordFailure(EMAIL, IP);
		}

		assertThatCode(() -> throttle.checkNotBlocked(EMAIL, IP)).doesNotThrowAnyException();
	}

	/**
	 * 5회→60초 · 6회→2분 · 7회→4분 · 8회→8분 · 9회 이후→15분(상한).
	 * 남은 초는 방금 계산한 값이라 경계에서 1초 덜 나올 수 있어 하한을 1초 넉넉히 잡는다.
	 */
	@Test
	void 임계값을_넘으면_실패마다_두_배로_늘고_상한에서_멈춘다() {
		for (int attempt = 0; attempt < 5; attempt++) {
			throttle.recordFailure(EMAIL, IP);
		}
		assertThat(retryAfterSeconds()).isBetween(59L, 60L);

		for (long expected : new long[]{120, 240, 480, 900, 900}) {
			throttle.recordFailure(EMAIL, IP);
			assertThat(retryAfterSeconds()).isBetween(expected - 1, expected);
		}
	}

	@Test
	void 성공하면_카운터를_버린다() {
		for (int attempt = 0; attempt < 5; attempt++) {
			throttle.recordFailure(EMAIL, IP);
		}

		throttle.reset(EMAIL, IP);

		assertThatCode(() -> throttle.checkNotBlocked(EMAIL, IP)).doesNotThrowAnyException();
	}

	@Test
	void 이메일과_IP를_함께_키로_쓴다() {
		for (int attempt = 0; attempt < 5; attempt++) {
			throttle.recordFailure(EMAIL, IP);
		}

		// 같은 이메일이라도 다른 자리(IP)는 막히지 않는다. 이메일만으로 세면
		// 남의 이메일에 다섯 번 틀리는 것만으로 그 사람을 막을 수 있다.
		assertThatCode(() -> throttle.checkNotBlocked(EMAIL, "198.51.100.7")).doesNotThrowAnyException();
		// 같은 자리에서 다른 이메일도 마찬가지다.
		assertThatCode(() -> throttle.checkNotBlocked("other@example.com", IP)).doesNotThrowAnyException();
	}

	@Test
	void 대소문자와_공백이_달라도_같은_카운터로_센다() {
		for (int attempt = 0; attempt < 5; attempt++) {
			throttle.recordFailure(" LEAD@example.com ", IP);
		}

		assertThatThrownBy(() -> throttle.checkNotBlocked(EMAIL, IP)).isInstanceOf(ApiException.class);
	}

	@Test
	void 차단은_초_단위_남은_시간을_실어_보낸다() {
		for (int attempt = 0; attempt < 5; attempt++) {
			throttle.recordFailure(EMAIL, IP);
		}

		assertThatThrownBy(() -> throttle.checkNotBlocked(EMAIL, IP))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(AuthErrorCode.LOGIN_TEMPORARILY_BLOCKED);
					assertThat(exception.errorCode().status().value()).isEqualTo(429);
					// 0을 주면 화면이 즉시 재시도해 또 막힌다.
					assertThat(exception.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
				});
	}

	private long retryAfterSeconds() {
		try {
			throttle.checkNotBlocked(EMAIL, IP);
		} catch (ApiException exception) {
			return exception.retryAfterSeconds();
		}
		throw new AssertionError("차단되지 않았다");
	}
}
