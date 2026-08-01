package com.bigproject.backend.domain.organization.presentation;

import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * organization/operations 도메인의 오류 응답. global의 {@code ErrorResponse}에 {@code code}가 없어
 * 목업 케이스 계약(에러 코드로 분기)을 만족시킬 수 없어서 이 도메인 전용으로 둔다.
 */
@Schema(description = "오류 응답 (목업 케이스 계약의 에러 코드 포함)")
public record OrganizationErrorResponse(
		Instant timestamp,
		int status,

		@Schema(description = "HTTP 상태 문구", example = "CONFLICT")
		String error,

		@Schema(description = "목업 케이스 계약의 에러 코드. 프론트는 이 값으로 분기한다.", example = "LAST_OPERATOR")
		OrganizationErrorCode code,

		String message
) {
	public static OrganizationErrorResponse of(OrganizationErrorCode code, String message) {
		return new OrganizationErrorResponse(
				Instant.now(),
				code.status().value(),
				code.status().name(),
				code,
				message
		);
	}
}
