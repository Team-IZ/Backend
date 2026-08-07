package com.bigproject.backend.domain.submission.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 교육생이 입력한 저장소 주소를 정규화한다. <b>제출 시점 백엔드가 할 수 있는 검사는 이것이 전부다.</b>
 *
 * <p>저장소가 실제로 존재하는지, 접근 가능한지, 브랜치가 있는지는 GitHub API를 불러야 알 수 있고
 * 그 주체는 AI 서버다. 따라서 여기를 통과했다고 저장소가 유효한 것은 아니며, 그런 실패는 마감 후
 * 분석 단계에서 {@code REPO_NOT_FOUND} 등으로 드러난다.
 *
 * <p>정규화 결과는 {@code repository_verification.normalized_repo_url}에 저장되고,
 * {@code repository.normalized_repo_url}과 대조해 같은 저장소인지 판정하는 데 쓰인다. 그래서
 * 대소문자·{@code .git} 접미사·후행 슬래시처럼 같은 저장소를 다르게 보이게 하는 요소를 전부 제거한다.
 *
 * @param original 교육생이 입력한 원문. {@code repository.repo_url}에 그대로 저장한다. 정규화값만 남기면
 *                 화면에 "내가 낸 주소"를 되돌려줄 수 없고, 대소문자가 접힌 주소가 표시된다.
 */
public record GithubRepositoryUrl(
		String original, String normalized, String ownerLogin, String repositoryName) {

	private static final Set<String> ALLOWED_HOSTS = Set.of("github.com", "www.github.com");

	/** GitHub 사용자·조직·저장소 이름에 허용되는 문자. 경로 순회나 질의 문자열이 섞여 들어오는 것을 막는다. */
	private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

	public static GithubRepositoryUrl parse(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new SubmissionException(SubmissionErrorCode.INVALID_REPOSITORY_URL);
		}

		String trimmed = rawUrl.trim();
		// scheme 없이 "github.com/o/r"만 붙여넣는 경우가 흔하다. URI가 이를 상대 경로로 읽어 host를 못 찾는다.
		if (!trimmed.contains("://")) {
			trimmed = "https://" + trimmed;
		}

		URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException exception) {
			throw new SubmissionException(SubmissionErrorCode.INVALID_REPOSITORY_URL);
		}

		String host = uri.getHost();
		if (host == null) {
			throw new SubmissionException(SubmissionErrorCode.INVALID_REPOSITORY_URL);
		}
		if (!ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
			throw new SubmissionException(SubmissionErrorCode.UNSUPPORTED_HOST);
		}

		String path = uri.getPath() == null ? "" : uri.getPath();
		String[] segments = path.split("/");
		// 앞의 빈 조각을 포함해 "", owner, repo 세 개여야 한다. 더 깊은 경로(/tree/main 등)는 저장소 주소가 아니다.
		if (segments.length != 3) {
			throw new SubmissionException(SubmissionErrorCode.INVALID_REPOSITORY_URL);
		}

		String owner = segments[1];
		String repository = stripGitSuffix(segments[2]);
		if (!SEGMENT.matcher(owner).matches() || !SEGMENT.matcher(repository).matches()) {
			throw new SubmissionException(SubmissionErrorCode.INVALID_REPOSITORY_URL);
		}

		// GitHub의 owner·repo는 대소문자를 구분하지 않는다. 소문자로 접어야 같은 저장소가 한 값으로 모인다.
		String normalized = "https://github.com/%s/%s".formatted(
				owner.toLowerCase(Locale.ROOT),
				repository.toLowerCase(Locale.ROOT)
		);
		return new GithubRepositoryUrl(rawUrl.trim(), normalized, owner, repository);
	}

	private static String stripGitSuffix(String segment) {
		return segment.regionMatches(true, segment.length() - 4, ".git", 0, 4)
				? segment.substring(0, segment.length() - 4)
				: segment;
	}
}
