package com.bigproject.backend.domain.submission.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 제출 시점에 백엔드가 수행하는 유일한 검사다. 저장소 존재·접근 가능 여부는 여기서 판정하지 않는다.
 */
class GithubRepositoryUrlTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"https://github.com/Team-IZ/Mini-Project-3",
			"https://github.com/Team-IZ/Mini-Project-3.git",
			"https://github.com/Team-IZ/Mini-Project-3/",
			"http://www.github.com/Team-IZ/Mini-Project-3",
			// scheme 없이 붙여넣는 경우가 흔하다.
			"github.com/Team-IZ/Mini-Project-3",
			"  https://github.com/Team-IZ/Mini-Project-3  "
	})
	void collapsesEquivalentFormsOntoOneNormalizedUrl(String rawUrl) {
		// repository.normalized_repo_url 과 대조해 같은 저장소인지 판정하므로 한 값으로 모여야 한다.
		assertThat(GithubRepositoryUrl.parse(rawUrl).normalized())
				.isEqualTo("https://github.com/team-iz/mini-project-3");
	}

	@Test
	void keepsOriginalCasingForDisplay() {
		GithubRepositoryUrl parsed = GithubRepositoryUrl.parse("https://github.com/Team-IZ/Mini-Project-3.git");

		assertThat(parsed.ownerLogin()).isEqualTo("Team-IZ");
		assertThat(parsed.repositoryName()).isEqualTo("Mini-Project-3");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://gitlab.com/team-iz/p",
			"https://github.com.evil.example/team-iz/p"
	})
	void rejectsNonGithubHosts(String rawUrl) {
		assertThatThrownBy(() -> GithubRepositoryUrl.parse(rawUrl))
				.isInstanceOf(SubmissionException.class)
				.extracting(exception -> ((SubmissionException) exception).errorCode())
				.isEqualTo(SubmissionErrorCode.UNSUPPORTED_HOST);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"   ",
			"https://github.com/team-iz",
			// 저장소 루트가 아닌 더 깊은 경로는 저장소 주소가 아니다.
			"https://github.com/team-iz/p/tree/main",
			"https://github.com/team-iz/p%20q",
			"not a url at all"
	})
	void rejectsMalformedRepositoryUrls(String rawUrl) {
		assertThatThrownBy(() -> GithubRepositoryUrl.parse(rawUrl))
				.isInstanceOf(SubmissionException.class)
				.extracting(exception -> ((SubmissionException) exception).errorCode())
				.isEqualTo(SubmissionErrorCode.INVALID_REPOSITORY_URL);
	}

	@Test
	void rejectsNullUrl() {
		assertThatThrownBy(() -> GithubRepositoryUrl.parse(null))
				.isInstanceOf(SubmissionException.class);
	}
}
