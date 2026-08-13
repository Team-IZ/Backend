package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 세션 도메인의 오류 응답. global의 {@code ErrorResponse}에는 {@code code}가 없어 분기에 쓸 수 없다.
 *
 * <p>세션은 전체화면이라 오류가 곧 막다른 길이다. 화면이 "다시 제출해 보세요"와 "이 시험은 끝났어요"를
 * 가르려면 문구가 아니라 코드가 필요하다.
 */
@Schema(description = "검증 세션 오류 응답")
public record SessionErrorResponse(
		Instant timestamp,
		int status,

		@Schema(description = "HTTP 상태 문구", example = "CONFLICT")
		String error,

		@Schema(description = "프론트가 분기에 쓰는 에러 코드", example = "SESSION_TIMEOUT")
		SessionErrorCode code,

		String message
) {
	public static SessionErrorResponse of(SessionErrorCode code, String message) {
		return new SessionErrorResponse(
				Instant.now(),
				code.status().value(),
				code.status().name(),
				code,
				message
		);
	}
}
