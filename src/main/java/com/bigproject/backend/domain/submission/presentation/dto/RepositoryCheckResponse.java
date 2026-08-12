package com.bigproject.backend.domain.submission.presentation.dto;

import com.bigproject.backend.domain.submission.domain.GithubRepositoryUrl;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 저장소 사전 확인 결과.
 *
 * <p><b>{@code ok=true}는 "주소가 저장소 주소의 모양을 갖췄다"까지만 뜻한다.</b> 저장소가 실제로
 * 존재하는지·공개인지·브랜치가 있는지는 GitHub API를 불러야 알 수 있고 그 주체는 AI 서버다
 * ({@link GithubRepositoryUrl} 주석 참고). 그 실패는 분석 단계에서 {@code REPO_NOT_FOUND}·
 * {@code REPOSITORY_ACCESS_DENIED}로 드러난다.
 *
 * <p>그래서 이 응답에 {@code ok=false}가 없다 — 형식이 틀리면 4xx로 나가고, 200이면 항상
 * {@code true}다. boolean 하나에 "형식 불일치"와 "저장소 없음"을 겹쳐 담으면 화면이 두 사건을
 * 같은 분기로 처리하게 되는데, 전자는 입력을 고치면 되고 후자는 ZIP으로 갈아타야 하는 서로 다른 안내다.
 *
 * @param normalizedUrl 정규화 주소. 화면이 "이 저장소가 맞습니까?" 확인 문구에 쓴다 —
 *                      대소문자·{@code .git}이 접힌 뒤의 값이라 오타를 눈으로 잡을 수 있다.
 */
@Schema(description = "저장소 사전 확인 결과")
public record RepositoryCheckResponse(
		@Schema(description = "형식·호스트 확인 통과. 200 응답에서는 항상 `true`다", example = "true")
		boolean ok,

		@Schema(description = "정규화된 저장소 주소", example = "https://github.com/team-iz/backend")
		String normalizedUrl,

		@Schema(description = "저장소 소유자(사용자·조직) 로그인", example = "Team-IZ")
		String ownerLogin,

		@Schema(description = "저장소 이름", example = "backend")
		String repositoryName
) {

	public static RepositoryCheckResponse of(GithubRepositoryUrl url) {
		return new RepositoryCheckResponse(
				true, url.normalized(), url.ownerLogin(), url.repositoryName());
	}
}
