package com.bigproject.backend.domain.submission.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 제출 전 저장소 주소 사전 확인 요청.
 *
 * <p>회차를 받지 않는다. 이 확인은 <b>주소 자체</b>에 대한 판정이라 회차·팀·마감과 무관하고,
 * 회차를 받으면 화면이 제출 폼을 그리기 전에도 부를 수 있어야 하는 요구와 어긋난다.
 */
@Schema(description = "저장소 사전 확인 요청")
public record RepositoryCheckRequest(
		@Schema(description = """
				확인할 GitHub 저장소 주소. `https://` 를 생략해도 되고 `.git` 접미사·후행 슬래시가
				붙어 있어도 된다 — 제출 시점과 **같은 정규화 규칙**을 쓴다.""",
				example = "https://github.com/Team-IZ/backend")
		@NotBlank(message = "저장소 주소를 입력해 주세요.")
		String repoUrl
) {
}
