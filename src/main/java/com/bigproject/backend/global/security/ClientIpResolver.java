package com.bigproject.backend.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 프록시 뒤에서 <b>진짜 클라이언트 IP</b>를 뽑는다.
 *
 * <p><b>왜 필요한가.</b> 이 서비스는 Railway 프록시 뒤에 있다. 그래서
 * {@code HttpServletRequest.getRemoteAddr()}가 돌려주는 것은 브라우저가 아니라 <b>프록시의 주소</b>다.
 * 프록시는 여러 대라 그 주소가 <b>요청마다 달라진다.</b> 그 값을 연속 실패 차단의 키에 넣으면
 * 같은 사람이 다섯 번 틀려도 키가 다섯 개로 흩어져 <b>카운터가 임계값에 닿지 못한다</b> —
 * 4차 요청서 Q1이 관측한 "429와 400이 번갈아 나온다"가 정확히 이 모양이다.
 *
 * <p><b>어떤 값을 고르나 — 오른쪽에서 {@code trustedProxyCount}번째.</b>
 * {@code X-Forwarded-For}는 홉마다 <b>자기가 본 상대의 주소를 뒤에 붙이는</b> 헤더다.
 * 프록시가 하나면 마지막 항목이 곧 그 프록시가 직접 본 주소, 즉 진짜 클라이언트다.
 *
 * <pre>
 * 클라이언트가 위조해서 보냄 : X-Forwarded-For: 198.51.100.9
 * Railway 프록시가 덧붙임    : X-Forwarded-For: 198.51.100.9, 203.0.113.42
 *                                              └ 위조값(무시)   └ 실제 클라이언트(우리가 쓰는 값)
 * </pre>
 *
 * <p><b>그래서 헤더 위조로 차단을 피할 수 없다.</b> 공격자가 앞에 무엇을 붙이든 그 값은 왼쪽으로
 * 밀려나고, 우리가 읽는 자리에는 프록시가 <b>직접 관측한</b> 주소만 온다. 반대로 왼쪽 끝(leftmost)을
 * 쓰는 흔한 구현은 위조에 그대로 뚫린다 — 요청마다 아무 값이나 넣어 차단을 무한히 우회할 수 있고,
 * 남의 IP를 적어 <b>그 사람을 막을</b> 수도 있다. 그래서 여기서는 leftmost를 쓰지 않는다.
 *
 * <p><b>앞단 홉 수가 바뀌면 설정도 바꿔야 한다.</b> Railway 앞에 CDN을 하나 더 두면 체인이 한 칸
 * 길어지므로 {@code CLIENT_IP_TRUSTED_PROXY_COUNT}를 2로 올려야 한다. 값이 실제보다 크면 헤더가
 * 모자라 {@code getRemoteAddr()}로 떨어지고(= 지금의 증상이 그대로 돌아온다), 값이 실제보다 작으면
 * 프록시 자신의 주소를 클라이언트로 읽는다. 그래서 <b>처음 관측한 체인을 로그로 한 번 남긴다</b> —
 * 배포 직후 그 한 줄만 보면 값이 맞는지 바로 확인할 수 있다.
 *
 * <p><b>헤더가 없으면</b> {@code getRemoteAddr()}로 떨어진다. 로컬 개발·테스트가 그 경로다.
 */
@Component
public class ClientIpResolver {
	private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
	private static final String DEFAULT_HEADER = "X-Forwarded-For";

	private final String headerName;
	private final int trustedProxyCount;
	private final AtomicBoolean firstObservationLogged = new AtomicBoolean();

	public ClientIpResolver(
			@Value("${web.client-ip.header:X-Forwarded-For}") String headerName,
			@Value("${web.client-ip.trusted-proxy-count:1}") int trustedProxyCount
	) {
		this.headerName = headerName == null || headerName.isBlank() ? DEFAULT_HEADER : headerName.trim();
		// 0이면 헤더를 아예 믿지 않는다 — 프록시 없이 직접 노출되는 환경에서 쓰는 설정이다.
		this.trustedProxyCount = Math.max(0, trustedProxyCount);
	}

	/** 진짜 클라이언트 IP. 알아낼 수 없으면 {@link ClientIpAddresses#UNKNOWN}이다. */
	public String resolve(HttpServletRequest request) {
		List<String> chain = forwardedChain(request);
		String remoteAddress = request.getRemoteAddr();
		String fromChain = fromTrustedHop(chain);
		String resolved = fromChain != null ? fromChain : ClientIpAddresses.normalize(remoteAddress);
		logObservation(chain, remoteAddress, resolved, fromChain != null);
		return resolved;
	}

	private String fromTrustedHop(List<String> chain) {
		if (trustedProxyCount == 0 || chain.size() < trustedProxyCount) {
			return null;
		}
		return ClientIpAddresses.normalizeOrNull(chain.get(chain.size() - trustedProxyCount));
	}

	/**
	 * 헤더를 항목 목록으로 편다. 같은 헤더가 여러 줄로 올 수도 있고(HTTP가 허용한다)
	 * 한 줄에 쉼표로 여러 개가 올 수도 있어 둘 다 같은 목록으로 합친다.
	 */
	private List<String> forwardedChain(HttpServletRequest request) {
		List<String> chain = new ArrayList<>();
		Enumeration<String> values = request.getHeaders(headerName);
		if (values == null) {
			return chain;
		}
		while (values.hasMoreElements()) {
			String value = values.nextElement();
			if (value == null) {
				continue;
			}
			for (String part : value.split(",")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) {
					chain.add(trimmed);
				}
			}
		}
		return chain;
	}

	/**
	 * 처음 한 번만 INFO로 남긴다. 매 요청 남기면 로그인 로그가 전부 이 줄로 덮이고,
	 * 안 남기면 {@code trustedProxyCount}가 맞는지 확인할 방법이 없다.
	 */
	private void logObservation(List<String> chain, String remoteAddress, String resolved, boolean fromHeader) {
		if (firstObservationLogged.compareAndSet(false, true)) {
			log.info(
					"client-ip 첫 관측 instance={} header={} chain={} chainSize={} trustedProxyCount={} remoteAddr={} resolved={} source={}",
					InstanceIdentity.id(), headerName, chain, chain.size(), trustedProxyCount,
					remoteAddress, resolved, fromHeader ? "header" : "remoteAddr"
			);
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug(
					"client-ip instance={} chain={} remoteAddr={} resolved={} source={}",
					InstanceIdentity.id(), chain, remoteAddress, resolved, fromHeader ? "header" : "remoteAddr"
			);
		}
	}
}
