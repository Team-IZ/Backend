package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** submission 도메인의 오류 응답. global의 {@code ErrorResponse}에는 {@code code}가 없어 분기에 쓸 수 없다. */
@Schema(description = "코드 제출·분석 조회 오류 응답")
public record SubmissionErrorResponse(
		Instant timestamp,
		int status,

		@Schema(description = "HTTP 상태 문구", example = "CONFLICT")
		String error,

		@Schema(description = "프론트가 분기에 쓰는 에러 코드", example = "SUBMISSION_DEADLINE_PASSED")
		SubmissionErrorCode code,

		String message
) {
	public static SubmissionErrorResponse of(SubmissionErrorCode code, String message) {
		return new SubmissionErrorResponse(
				Instant.now(),
				code.status().value(),
				code.status().name(),
				code,
				message
		);
	}
}
