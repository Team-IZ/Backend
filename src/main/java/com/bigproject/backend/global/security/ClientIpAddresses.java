package com.bigproject.backend.global.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * IP 문자열을 <b>같은 클라이언트면 언제나 같은 문자열</b>이 되도록 정규화한다.
 *
 * <p>이것이 따로 있는 이유는 하나다. 연속 실패 차단은 <b>키가 같아야</b> 카운터가 쌓인다.
 * 같은 사람이 보낸 요청인데 표기가 조금씩 다르면({@code ::FFFF:1.2.3.4}와 {@code 1.2.3.4},
 * {@code [::1]:443}과 {@code ::1}, 대소문자, 존 식별자 {@code %eth0}) 키가 갈려서
 * <b>차단 장치가 있는데도 새어나간다.</b> 4차 요청서 Q1이 관측한 증상이 정확히 그 모양이었다.
 *
 * <p><b>DNS를 절대 타지 않는다.</b> {@link InetAddress#getByName(String)}은 리터럴이 아니면
 * 이름 조회로 넘어간다. 프록시 헤더 값은 외부에서 들어온 문자열이라, 조회로 넘어가는 순간
 * 로그인 한 번이 외부 DNS 왕복을 유발하는 통로가 된다. 그래서 IPv4는 직접 검증하고,
 * IPv6는 <b>대괄호로 감싸</b> 넘긴다 — 대괄호가 붙으면 자바는 파싱 실패를 이름 조회로 넘기지 않고
 * 그 자리에서 예외로 끝낸다.
 */
public final class ClientIpAddresses {

	/** IP를 알 수 없을 때 쓰는 값. 기존 {@code TokenRequestMetadata} 기본값과 같다. */
	public static final String UNKNOWN = "0.0.0.0";

	/** IPv6 최장 표기(IPv4 매핑 포함) 길이. 이보다 길면 IP가 아니다. */
	private static final int MAX_LITERAL_LENGTH = 45;

	/** IPv6를 차단 키로 쓸 때 묶는 대역 크기. 앞 4그룹 = /64. */
	private static final int IPV6_PREFIX_GROUPS = 4;

	private ClientIpAddresses() {
	}

	/**
	 * 정규화한 IP를 돌려준다. IP로 볼 수 없으면 {@code null}이다.
	 *
	 * <p>{@code null}과 {@link #UNKNOWN}을 구분하는 것이 중요하다 — 호출부가 "이 값은 못 믿겠으니
	 * 다음 후보를 보자"와 "후보가 다 떨어졌다"를 구분해야 하기 때문이다.
	 */
	public static String normalizeOrNull(String raw) {
		if (raw == null) {
			return null;
		}
		String value = stripQuotes(raw.trim());
		value = stripPort(value);
		value = stripZoneId(value);
		value = value.trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty() || value.length() > MAX_LITERAL_LENGTH) {
			return null;
		}
		if (isIpv4Literal(value)) {
			return value;
		}
		if (value.indexOf(':') < 0) {
			// 점도 콜론도 없거나 IPv4 형태가 아니면 IP가 아니다. RFC 7239의 obfuscated
			// 식별자(_hidden)나 unknown이 여기로 온다.
			return null;
		}
		return normalizeIpv6(value);
	}

	/** 정규화한 IP를 돌려주되, IP로 볼 수 없으면 {@link #UNKNOWN}으로 떨어진다. */
	public static String normalize(String raw) {
		String normalized = normalizeOrNull(raw);
		return normalized == null ? UNKNOWN : normalized;
	}

	/**
	 * 연속 실패 차단의 키로 쓸 형태로 만든다.
	 *
	 * <p>IPv4는 정규화한 주소 그대로다. <b>IPv6만 /64로 묶는다.</b> IPv6 단말은 프라이버시 확장으로
	 * 주소 뒷부분을 수시로 바꾸는데, 그대로 키에 넣으면 <b>요청마다 키가 달라져 카운터가 안 쌓인다</b> —
	 * 지금 고치고 있는 것과 정확히 같은 종류의 누수다. /64는 보통 회선 하나에 대응한다.
	 * 사무실 LAN처럼 여러 사람이 한 /64를 쓰는 경우가 있지만, 키에는 이메일도 함께 들어가므로
	 * 막히는 범위는 "그 대역에서 <b>그 계정으로</b> 로그인하려는 사람"까지다.
	 */
	public static String throttleKey(String ip) {
		String normalized = normalizeOrNull(ip);
		if (normalized == null) {
			return UNKNOWN;
		}
		if (normalized.indexOf(':') < 0) {
			return normalized;
		}
		String[] groups = normalized.split(":");
		StringBuilder prefix = new StringBuilder();
		for (int index = 0; index < IPV6_PREFIX_GROUPS && index < groups.length; index++) {
			if (index > 0) {
				prefix.append(':');
			}
			prefix.append(groups[index]);
		}
		return prefix + "::/64";
	}

	private static String stripQuotes(String value) {
		if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
			return value.substring(1, value.length() - 1).trim();
		}
		return value;
	}

	/**
	 * {@code [::1]:443} · {@code 1.2.3.4:5678}에서 포트를 떼어낸다.
	 *
	 * <p>콜론이 하나뿐이면 IPv6일 수 없으므로(가장 짧은 IPv6인 {@code ::}도 콜론이 둘이다)
	 * 그 콜론은 포트 구분자다. 대괄호가 없는 IPv6는 포트를 붙일 방법이 없으니 그대로 둔다.
	 */
	private static String stripPort(String value) {
		if (value.startsWith("[")) {
			int closing = value.indexOf(']');
			return closing < 0 ? "" : value.substring(1, closing);
		}
		int firstColon = value.indexOf(':');
		if (firstColon >= 0 && firstColon == value.lastIndexOf(':')) {
			return value.substring(0, firstColon);
		}
		return value;
	}

	private static String stripZoneId(String value) {
		int zone = value.indexOf('%');
		return zone < 0 ? value : value.substring(0, zone);
	}

	private static boolean isIpv4Literal(String value) {
		int octets = 0;
		int digits = 0;
		int octet = 0;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '.') {
				if (digits == 0) {
					return false;
				}
				octets++;
				digits = 0;
				octet = 0;
				continue;
			}
			if (character < '0' || character > '9') {
				return false;
			}
			// 0으로 시작하는 여러 자리(010)는 8진수로 읽힐 여지가 있어 받지 않는다.
			if (digits == 1 && octet == 0) {
				return false;
			}
			octet = octet * 10 + (character - '0');
			digits++;
			if (digits > 3 || octet > 255) {
				return false;
			}
		}
		return octets == 3 && digits > 0;
	}

	/**
	 * IPv6를 자바의 정규 표기로 되돌린다. {@code ::ffff:1.2.3.4}는 자바가 IPv4로 되돌려 주므로
	 * IPv4-mapped와 그냥 IPv4가 같은 키가 된다.
	 */
	private static String normalizeIpv6(String value) {
		try {
			// 대괄호가 DNS 조회로 새는 것을 막는다 — 클래스 주석 참고.
			String host = InetAddress.getByName("[" + value + "]").getHostAddress();
			int scope = host.indexOf('%');
			return (scope < 0 ? host : host.substring(0, scope)).toLowerCase(Locale.ROOT);
		} catch (UnknownHostException exception) {
			return null;
		}
	}
}
