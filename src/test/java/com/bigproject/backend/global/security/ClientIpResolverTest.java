package com.bigproject.backend.global.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
	private static final String CLIENT = "203.0.113.42";

	private final ClientIpResolver resolver = new ClientIpResolver("X-Forwarded-For", 1);

	/**
	 * 이 서비스의 실제 배치다. Railway 프록시가 붙인 마지막 항목이 진짜 클라이언트이고,
	 * {@code getRemoteAddr()}는 프록시 자신의 주소다.
	 */
	@Test
	void 프록시가_하나면_체인의_마지막_항목을_쓴다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("fd00::a1");
		request.addHeader("X-Forwarded-For", CLIENT);

		assertThat(resolver.resolve(request)).isEqualTo(CLIENT);
	}

	/**
	 * <b>이 테스트가 헤더 위조 우회를 막는다.</b> 공격자가 앞에 무엇을 적어 보내든 프록시가
	 * 자기가 본 주소를 뒤에 붙이므로, 위조값은 왼쪽으로 밀려나고 우리가 읽는 자리에 오지 못한다.
	 * 왼쪽 끝을 쓰는 구현이었다면 여기서 위조값이 그대로 나왔을 것이다.
	 */
	@Test
	void 클라이언트가_앞에_붙인_위조값은_쓰이지_않는다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("fd00::a1");
		request.addHeader("X-Forwarded-For", "198.51.100.9, " + CLIENT);

		assertThat(resolver.resolve(request)).isEqualTo(CLIENT);
	}

	/** 같은 헤더가 여러 줄로 와도 한 체인으로 읽는다 — HTTP가 허용하는 형태다. */
	@Test
	void 헤더가_여러_줄로_와도_한_체인으로_읽는다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("fd00::a1");
		request.addHeader("X-Forwarded-For", "198.51.100.9");
		request.addHeader("X-Forwarded-For", CLIENT);

		assertThat(resolver.resolve(request)).isEqualTo(CLIENT);
	}

	/** 앞단에 CDN을 하나 더 두면 체인이 한 칸 길어진다. 설정을 올리면 그만큼 왼쪽을 본다. */
	@Test
	void 신뢰_홉_수만큼_오른쪽에서_센다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("fd00::a1");
		request.addHeader("X-Forwarded-For", CLIENT + ", 198.51.100.9");

		assertThat(new ClientIpResolver("X-Forwarded-For", 2).resolve(request)).isEqualTo(CLIENT);
	}

	/** 로컬 개발·테스트 경로다. 헤더가 없으면 접속 주소를 그대로 쓴다. */
	@Test
	void 헤더가_없으면_접속_주소로_떨어진다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("127.0.0.1");

		assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
	}

	/**
	 * 홉 수를 실제보다 크게 잡으면 체인이 모자란다. 이때 억지로 있는 값을 쓰면
	 * 위조값을 믿게 되므로, 차라리 접속 주소로 떨어진다.
	 */
	@Test
	void 체인이_설정보다_짧으면_접속_주소로_떨어진다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.1");
		request.addHeader("X-Forwarded-For", CLIENT);

		assertThat(new ClientIpResolver("X-Forwarded-For", 2).resolve(request)).isEqualTo("198.51.100.1");
	}

	@Test
	void 홉_수가_0이면_헤더를_아예_믿지_않는다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.1");
		request.addHeader("X-Forwarded-For", CLIENT);

		assertThat(new ClientIpResolver("X-Forwarded-For", 0).resolve(request)).isEqualTo("198.51.100.1");
	}

	@Test
	void 체인의_값이_IP가_아니면_접속_주소로_떨어진다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.1");
		request.addHeader("X-Forwarded-For", "unknown");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
	}

	@Test
	void 아무것도_알_수_없으면_알_수_없음을_돌려준다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("");

		assertThat(resolver.resolve(request)).isEqualTo(ClientIpAddresses.UNKNOWN);
	}
}
