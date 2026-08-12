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
		List<FieldError> fieldErrors,

		@Schema(description = """
				다시 시도할 수 있을 때까지 남은 초. 일시 차단(429)에서만 실리고 그 외에는 키 자체가 없다.

				같은 값이 `Retry-After` 헤더로도 나간다 — 화면이 남은 시간을 세려면 본문에서
				읽는 편이 간단하고, 프록시·클라이언트 라이브러리의 공통 재시도 처리는 헤더를 본다.""",
				nullable = true, example = "300")
		Long retryAfter
) {

	/** 어떤 입력이 왜 거절됐는지. 화면이 입력칸 옆에 그대로 붙일 수 있게 필드명을 함께 준다. */
	@Schema(name = "FieldError", description = "필드 단위 검증 실패")
	public record FieldError(

			@Schema(description = "요청 본문의 필드명", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "dataRetentionDays")
			String field,

			@Schema(description = """
					거절 사유를 나타내는 안정 코드. **화면 문구는 이 값으로 정한다.**
					Bean Validation 제약 이름을 대문자 스네이크로 옮긴 값이며(`@NotBlank` → `NOT_BLANK`),
					제약을 알 수 없으면 `INVALID`다.

					`message`가 아니라 이 값으로 분기해야 하는 이유: `message`는 Bean Validation 기본 문구라
					영문이고(`must not be blank`) 로케일·라이브러리 버전에 따라 바뀐다 — 계약이 아니다.""",
					requiredMode = Schema.RequiredMode.REQUIRED, example = "NOT_BLANK")
			String code,

			@Schema(description = "거절 사유(사람이 읽는 기본 문구). 화면에 그대로 쓰지 말 것 — 바뀔 수 있다",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "90, 180, 365 중 하나여야 합니다.")
			String message
	) {

		/** 제약 이름을 모를 때 쓰는 값. 코드가 비는 것보다 낫다 — 프론트가 분기에서 빠뜨리지 않는다. */
		private static final String UNKNOWN_CONSTRAINT = "INVALID";

		/**
		 * Bean Validation 제약 이름({@code NotBlank}·{@code Size})을 코드로 옮긴다.
		 *
		 * <p>스프링은 이 이름을 {@code FieldError.getCode()}로, Jakarta 쪽은 애너테이션 타입으로 준다.
		 * 둘 다 같은 규칙으로 접어 두면 어느 경로로 온 검증 실패든 프론트가 같은 값으로 분기한다.
		 */
		public static FieldError of(String field, String constraintName, String message) {
			return new FieldError(field, toCode(constraintName), message);
		}

		private static String toCode(String constraintName) {
			if (constraintName == null || constraintName.isBlank()) {
				return UNKNOWN_CONSTRAINT;
			}
			// NotBlank → NOT_BLANK. 대문자 앞에 밑줄을 넣되 맨 앞은 빼고, 연속 대문자(URL 등)는 붙여 둔다.
			String snake = constraintName.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_");
			return snake.toUpperCase(java.util.Locale.ROOT);
		}
	}

	/** 안정 코드가 따로 없는 경우. {@code error}를 그대로 코드로 쓴다. */
	public static ErrorResponse of(int status, String error, String message) {
		return of(status, error, error, message);
	}

	public static ErrorResponse of(int status, String error, String code, String message) {
		return new ErrorResponse(Instant.now(), status, error, code, message, null, null);
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
				fieldErrors == null || fieldErrors.isEmpty() ? null : List.copyOf(fieldErrors),
				null
		);
	}

	/** 일시 차단처럼 "언제 다시 되는지"가 답의 일부인 경우. */
	public static ErrorResponse of(HttpStatus status, String code, String message, Long retryAfterSeconds) {
		return new ErrorResponse(
				Instant.now(), status.value(), status.name(), code, message, null, retryAfterSeconds);
	}
}
