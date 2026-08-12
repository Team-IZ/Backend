package com.bigproject.backend.domain.submission.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "GitHub 저장소 URL 제출 요청")
public record CreateGithubSubmissionRequest(

		@Schema(description = "제출 대상 회차", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull
		UUID assessmentRoundId,

		@Schema(
				description = "교육생이 입력한 저장소 주소 원문. 형식·호스트만 검사하며 실제 접근 가능 여부는 마감 후 분석에서 판정한다.",
				example = "https://github.com/team-iz/mini-project-3",
				requiredMode = Schema.RequiredMode.REQUIRED
		)
		@NotBlank
		@Size(max = 2000)
		String repositoryUrl,

		@Schema(
				description = "분석할 브랜치. 비우면 AI 서버가 기본 브랜치를 선택해 resolvedBranch로 회신한다.",
				example = "main"
		)
		@Size(max = 255)
		String branch
) {
}
