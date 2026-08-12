package com.bigproject.backend.domain.submission.presentation.dto;

import com.bigproject.backend.domain.submission.domain.Submission;
import com.bigproject.backend.domain.submission.domain.SubmissionMethod;
import com.bigproject.backend.domain.submission.domain.SubmissionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "코드 제출 접수 결과")
public record SubmissionResponse(

		UUID submissionId,

		@Schema(description = "제출 수단", example = "GITHUB_URL")
		SubmissionMethod method,

		@Schema(
				description = "두 방식 모두 접수 즉시 ACCEPTED다. 내용 판정(저장소 접근 실패·빈 코드·git log 누락)은 "
						+ "제출이 아니라 분석 단계의 사건이므로 `GET /submissions/{submissionId}/analysis`의 "
						+ "`failureCode`로 드러난다.",
				example = "ACCEPTED"
		)
		SubmissionStatus status,

		@Schema(description = "마감 판정의 기준 시각. GitHub 제출은 저장소 확인 요청 접수 시각을 그대로 쓴다.")
		Instant submittedAt,

		@Schema(description = "팀·회차의 현재 제출인지 여부")
		boolean current,

		@Schema(description = "직전 제출. 첫 제출이면 null이다.", nullable = true)
		UUID supersedesSubmissionId,

		@Schema(description = "GitHub 제출의 저장소 확인 실행. 제출된 URL 원문은 이 행에만 남는다. ZIP이면 null이다.",
				nullable = true)
		UUID repositoryVerificationId,

		@Schema(description = "ZIP 제출의 아티팩트. GitHub 제출이면 null이다.", nullable = true)
		UUID artifactId
) {
	public static SubmissionResponse of(Submission submission, UUID artifactId) {
		return new SubmissionResponse(
				submission.getSubmissionId(),
				submission.getMethod(),
				submission.getStatus(),
				submission.getSubmittedAt(),
				submission.isCurrent(),
				submission.getSupersedesSubmissionId(),
				submission.getRepositoryVerificationId(),
				artifactId
		);
	}
}
