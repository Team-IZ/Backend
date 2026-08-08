package com.bigproject.backend.global.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 이 프로세스를 <b>다른 프로세스와 구분</b>하는 짧은 식별자. 로그에만 쓴다.
 *
 * <p>연속 실패 차단 카운터는 프로세스 메모리에 있다. 그래서 "차단이 새는" 증상이 나올 때
 * <b>같은 인스턴스 안에서 키가 흩어진 것</b>인지 <b>요청이 서로 다른 인스턴스로 나뉜 것</b>인지를
 * 구분해야 하는데, 로그만 봐서는 그 둘이 똑같이 생겼다. 로그 줄마다 이 값을 함께 남기면
 * 재현 10회의 로그를 훑는 것만으로 어느 쪽인지 바로 갈린다 — 값이 하나면 인스턴스는 하나다.
 *
 * <p>호스트명만으로는 부족하다. 재배포로 프로세스가 바뀌어도 호스트명이 같을 수 있고,
 * 그러면 <b>재시작으로 카운터가 날아간 것</b>이 인스턴스 다중으로 오해된다.
 * 그래서 프로세스마다 새로 뽑는 짧은 난수를 뒤에 붙인다.
 */
public final class InstanceIdentity {
	private static final int MAX_HOST_LENGTH = 12;
	private static final String ID = resolve();

	private InstanceIdentity() {
	}

	public static String id() {
		return ID;
	}

	private static String resolve() {
		String host = firstNonBlank(System.getenv("RAILWAY_REPLICA_ID"), System.getenv("HOSTNAME"));
		if (host == null) {
			try {
				host = InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException exception) {
				host = "unknown";
			}
		}
		if (host.length() > MAX_HOST_LENGTH) {
			host = host.substring(0, MAX_HOST_LENGTH);
		}
		return host + "/" + UUID.randomUUID().toString().substring(0, 6);
	}

	private static String firstNonBlank(String... candidates) {
		for (String candidate : candidates) {
			if (candidate != null && !candidate.isBlank()) {
				return candidate.trim();
			}
		}
		return null;
	}
}
