package com.bigproject.backend.global.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpAddressesTest {

	@Test
	void 포트와_대괄호와_존_식별자를_떼어낸다() {
		assertThat(ClientIpAddresses.normalizeOrNull("203.0.113.42:51514")).isEqualTo("203.0.113.42");
		assertThat(ClientIpAddresses.normalizeOrNull("[::1]:443")).isEqualTo("0:0:0:0:0:0:0:1");
		assertThat(ClientIpAddresses.normalizeOrNull("fe80::1%eth0")).isEqualTo("fe80:0:0:0:0:0:0:1");
		assertThat(ClientIpAddresses.normalizeOrNull("\"203.0.113.42\"")).isEqualTo("203.0.113.42");
	}

	/** 이것이 갈리면 같은 클라이언트가 카운터를 두 개 쓰게 된다. */
	@Test
	void IPv4_매핑_표기는_IPv4와_같은_값이_된다() {
		assertThat(ClientIpAddresses.normalizeOrNull("::ffff:203.0.113.42")).isEqualTo("203.0.113.42");
		assertThat(ClientIpAddresses.normalizeOrNull("::FFFF:203.0.113.42")).isEqualTo("203.0.113.42");
	}

	@Test
	void IPv6는_압축_표기와_완전_표기가_같은_값이_된다() {
		assertThat(ClientIpAddresses.normalizeOrNull("2001:db8::1"))
				.isEqualTo(ClientIpAddresses.normalizeOrNull("2001:0DB8:0000:0000:0000:0000:0000:0001"));
	}

	/**
	 * IP가 아닌 값은 {@code null}이다. 호출부가 "이 후보는 못 믿겠으니 다음을 보자"를 판단해야 한다.
	 * 이름을 받아 주면 로그인 한 번이 외부 DNS 조회를 부르는 통로가 된다.
	 */
	@Test
	void IP가_아닌_값은_받지_않는다() {
		assertThat(ClientIpAddresses.normalizeOrNull("unknown")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull("_hidden")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull("evil.example.com")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull("1.2.3.999")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull("1.2.3")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull("010.1.1.1")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull("")).isNull();
		assertThat(ClientIpAddresses.normalizeOrNull(null)).isNull();
	}

	@Test
	void 값을_못_믿으면_알_수_없음으로_떨어진다() {
		assertThat(ClientIpAddresses.normalize("unknown")).isEqualTo(ClientIpAddresses.UNKNOWN);
		assertThat(ClientIpAddresses.throttleKey("unknown")).isEqualTo(ClientIpAddresses.UNKNOWN);
	}

	@Test
	void 차단_키는_IPv4를_그대로_쓴다() {
		assertThat(ClientIpAddresses.throttleKey("203.0.113.42")).isEqualTo("203.0.113.42");
		assertThat(ClientIpAddresses.throttleKey("::ffff:203.0.113.42")).isEqualTo("203.0.113.42");
	}

	/**
	 * IPv6 단말은 프라이버시 확장으로 주소 뒷부분을 계속 바꾼다. 그대로 키에 넣으면
	 * 요청마다 키가 달라져 카운터가 쌓이지 않는다 — 지금 고치는 것과 같은 종류의 누수다.
	 */
	@Test
	void 차단_키는_IPv6를_64_대역으로_묶는다() {
		assertThat(ClientIpAddresses.throttleKey("2001:db8:abcd:12::1"))
				.isEqualTo("2001:db8:abcd:12::/64");
		assertThat(ClientIpAddresses.throttleKey("2001:db8:abcd:12:9999:8888:7777:6666"))
				.isEqualTo("2001:db8:abcd:12::/64");
		// 다른 대역은 여전히 다른 키다 — 무한정 넓게 묶지 않는다.
		assertThat(ClientIpAddresses.throttleKey("2001:db8:abcd:13::1"))
				.isNotEqualTo(ClientIpAddresses.throttleKey("2001:db8:abcd:12::1"));
	}
}
