package com.bigproject.backend.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * 모든 4xx·5xx 응답이 쓰는 <b>단일</b> 오류 스키마.
 *
 * <p>전에는 도메인마다 모양이 달랐다 — global {@code ErrorResponse}에는 코드가 없고,
 * organization은 {@code OrganizationErrorResponse}, reporting은 {@code ReportErrorResponse}로
 * 각자 코드를 실어 보냈다. 생성된 프론트 타입에서 오류가 세 가지 타입이 되어
 * 케이스 분기를 도메인마다 다시 짜야 했으므로 여기 하나로 합쳤다.
 *
 * <p><b>계약은 {@link #code}다.</b> 프론트는 이 값으로 분기하고 화면 문구는 프론트가 정한다.
 * {@link #message}는 로그·폴백용이라 언제든 바뀔 수 있다.
 *
 * <p>{@link #error}는 합치기 전 응답에 있던 필드라 하위호환으로 남겨 둔다. 새로 쓰지 않는다.
 */
@Schema(
		name = "ErrorResponse",
		description = """
				공통 오류 응답. 모든 4xx·5xx가 이 모양이다.

				프론트는 `code`로 분기한다 — `message`는 사람이 읽는 기본 문구라 바뀔 수 있다.
				응답별로 어떤 `code`가 오는지는 각 오퍼레이션 응답의 examples 키에 코드명으로 적혀 있다."""
)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(

		@Schema(description = "오류 발생 시각 (ISO-8601 UTC)", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-07T04:21:33.512Z")
		Instant timestamp,

		@Schema(description = "HTTP 상태 코드", requiredMode = Schema.RequiredMode.REQUIRED, example = "409")
		int status,

		@Schema(description = "HTTP 상태 문구. 통합 전 응답과의 하위호환용이며 분기에 쓰지 않는다.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "CONFLICT")
		String error,

		@Schema(description = "기계가 분기하는 안정 코드. 화면 문구는 프론트가 정한다.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "ORG_NAME_TAKEN")
		String code,

		@Schema(description = "사람이 읽는 기본 메시지(로그·폴백용)", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "이미 있는 기관명입니다.")
		String message,

		@Schema(description = "필드 단위 검증 실패 목록. 검증 오류(400)가 아니면 키 자체가 없다.",
				nullable = true)
		List<FieldError> fieldErrors
) {

	/** 어떤 입력이 왜 거절됐는지. 화면이 입력칸 옆에 그대로 붙일 수 있게 필드명을 함께 준다. */
	@Schema(name = "FieldError", description = "필드 단위 검증 실패")
	public record FieldError(

			@Schema(description = "요청 본문의 필드명", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "dataRetentionDays")
			String field,

			@Schema(description = "거절 사유", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "90, 180, 365 중 하나여야 합니다.")
			String message
	) {
	}

	/** 안정 코드가 따로 없는 경우. {@code error}를 그대로 코드로 쓴다. */
	public static ErrorResponse of(int status, String error, String message) {
		return of(status, error, error, message);
	}

	public static ErrorResponse of(int status, String error, String code, String message) {
		return new ErrorResponse(Instant.now(), status, error, code, message, null);
	}

	public static ErrorResponse of(HttpStatus status, String code, String message) {
		return of(status.value(), status.name(), code, message);
	}

	public static ErrorResponse of(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
		return new ErrorResponse(
				Instant.now(),
				status.value(),
				status.name(),
				code,
				message,
				fieldErrors == null || fieldErrors.isEmpty() ? null : List.copyOf(fieldErrors)
		);
	}
}
