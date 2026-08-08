package com.bigproject.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 허용된 클라이언트 Origin 목록. <b>CORS 필터와 서버의 Origin 검사가 같은 값을 보게</b> 하는 단일 출처다.
 *
 * <p>전에는 {@code SecurityConfig}와 {@code LoginClientValidator}가 각각
 * {@code auth.login.allowed-origins}를 읽어 <b>서로 다르게</b> 파싱했다 — CORS 쪽은 문자열을 그대로
 * 쓰고 Origin 검사 쪽은 정규화해서 비교했다. 그래서 {@code https://app.example.com/}처럼 끝에
 * 슬래시가 붙은 항목 하나로 두 층의 판정이 갈릴 수 있었고, 그 경우 증상은 "CORS는 통과했는데
 * 403이 난다"로 나타난다. 원인을 찾기 어려운 종류라 정의를 한 곳으로 모았다.
 *
 * <p><b>두 층은 막는 주체가 다르다.</b> CORS는 <b>브라우저</b>가 응답을 넘겨주지 않는 것이고,
 * 이 목록으로 하는 Origin 검사는 <b>서버</b>가 403으로 거절하는 것이다. 그래서 배포 도메인이
 * 목록에 없으면 로그인 자체가 안 된다 — "CORS 설정이라 배포 직전에 하면 된다"가 성립하지 않는다.
 *
 * <p><b>와일드카드는 기본으로 꺼 둔다.</b> Vercel 프리뷰 배포는 PR마다
 * {@code frontend-<해시>-<팀>.vercel.app}으로 URL이 바뀌어 고정 등록이 안 되므로 {@code *} 표기를
 * 지원하되, {@code https://*.vercel.app}은 남의 프로젝트까지 포함하는 범위라 상시로 열어 둘 값이
 * 아니다. 필요할 때 {@code auth.login.allow-origin-wildcards=true}로 켠다.
 */
@Component
public class AllowedOriginPolicy {

	private final Set<String> exactOrigins;
	private final List<Pattern> wildcardPatterns;
	private final List<String> corsOriginPatterns;

	public AllowedOriginPolicy(
			@Value("${auth.login.allowed-origins:http://localhost:5173}") String allowedOrigins,
			@Value("${auth.login.allow-origin-wildcards:false}") boolean allowWildcards
	) {
		Set<String> exact = new LinkedHashSet<>();
		List<Pattern> wildcards = new ArrayList<>();
		List<String> forCors = new ArrayList<>();

		for (String entry : Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList()) {
			if (entry.isEmpty()) {
				continue;
			}
			if (entry.contains("*")) {
				// 와일드카드를 끈 상태에서 목록에 남아 있으면 CORS 쪽에도 넘기지 않는다.
				// 한쪽만 통과하면 "CORS는 됐는데 403"이 되어 원인을 찾기 어렵다.
				if (allowWildcards) {
					wildcards.add(toWildcardPattern(entry));
					forCors.add(entry);
				}
				continue;
			}
			String normalized = normalize(entry);
			if (normalized == null) {
				continue;
			}
			exact.add(normalized);
			forCors.add(normalized);
		}

		this.exactOrigins = Set.copyOf(exact);
		this.wildcardPatterns = List.copyOf(wildcards);
		this.corsOriginPatterns = List.copyOf(forCors);
	}

	/** 요청 Origin이 허용 목록에 있는지. 형식이 올바르지 않은 값도 허용되지 않은 것으로 본다. */
	public boolean isAllowed(String origin) {
		String normalized = normalize(origin);
		if (normalized == null) {
			return false;
		}
		return exactOrigins.contains(normalized)
				|| wildcardPatterns.stream().anyMatch(pattern -> pattern.matcher(normalized).matches());
	}

	/**
	 * Spring CORS 설정에 넘기는 목록. {@code setAllowedOrigins}가 아니라
	 * {@code setAllowedOriginPatterns}에 넘긴다 — 전자는 {@code *}를 못 쓰고,
	 * 두 메서드에 목록을 갈라 담으면 다시 두 벌 관리가 된다.
	 */
	public List<String> corsOriginPatterns() {
		return corsOriginPatterns;
	}

	/**
	 * scheme·host·port만 남긴다. 브라우저가 보내는 {@code Origin}은 경로도 슬래시도 붙지 않는
	 * 형태이므로, 설정에 {@code https://app.example.com/}처럼 적혀 있어도 같은 값으로 취급해야 한다.
	 *
	 * @return 정규화한 origin, 형식이 올바르지 않으면 {@code null}
	 */
	public static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(value.trim());
			if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
					|| uri.getQuery() != null || uri.getFragment() != null
					|| (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
				return null;
			}
			String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
			return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	/**
	 * {@code https://*.vercel.app} → {@code https://[^./]+\.vercel\.app}.
	 * {@code *}는 점을 넘지 않으므로 {@code a.b.vercel.app}은 걸리지 않는다 —
	 * 프리뷰 URL은 라벨 하나만 바뀐다.
	 */
	private static Pattern toWildcardPattern(String entry) {
		String[] literals = entry.trim().toLowerCase(Locale.ROOT).split("\\*", -1);
		String regex = Arrays.stream(literals).map(Pattern::quote).reduce((left, right) -> left + "[^./]+" + right)
				.orElse("");
		return Pattern.compile(regex);
	}
}
