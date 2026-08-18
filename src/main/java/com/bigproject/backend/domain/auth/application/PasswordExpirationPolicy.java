package com.bigproject.backend.domain.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 비밀번호 유효기간(주기적 변경) 정책.
 *
 * <h2>기본값이 0(비활성)인 것이 핵심이다</h2>
 *
 * <p>변경 주기는 <b>기술이 아니라 운영이 정하는 값</b>이다(주기·예외 대상·안내 방법). 정책이 정해지기
 * 전에 임의의 기본값을 켜 두면 어느 날 갑자기 계정이 만료되는데, 그게 시연 당일이면 되돌릴 방법이 없다.
 * 그래서 <b>구조만 넣고 스위치는 꺼 둔다</b> — 정책이 확정되면 환경변수
 * {@code AUTH_PASSWORD_MAX_AGE_DAYS} 하나로 켜진다.
 *
 * <h2>{@code passwordChangedAt}이 없으면 만료로 보지 않는다</h2>
 *
 * <p>기준 시각이 없는데 만료로 판정하면 <b>로그인할 방법이 없는 계정</b>이 생긴다. 이 컬럼은 계정
 * 활성화·비밀번호 변경 시 채워지므로 정상 계정은 값이 있고, 없는 것은 데이터 결함이다 —
 * 결함을 로그인 차단으로 표현하면 원인을 찾기 어려운 장애가 된다.
 */
@Component
public class PasswordExpirationPolicy {

	/** {@link Duration#ZERO}면 비활성이다. */
	private final Duration maxAge;

	public PasswordExpirationPolicy(@Value("${auth.password.max-age-days:0}") int maxAgeDays) {
		this.maxAge = maxAgeDays > 0 ? Duration.ofDays(maxAgeDays) : Duration.ZERO;
	}

	public boolean enabled() {
		return !maxAge.isZero();
	}

	public boolean isExpired(Instant passwordChangedAt, Instant now) {
		if (!enabled() || passwordChangedAt == null) {
			return false;
		}
		return passwordChangedAt.plus(maxAge).isBefore(now);
	}
}
