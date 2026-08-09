package com.bigproject.backend.global.exception;

/**
 * 도메인 에러 코드를 실어 보내는 예외의 공통 상위 타입.
 *
 * <p><b>왜 도메인마다 만들지 않는가.</b> 전에는 도메인이 하나 늘 때마다 예외 클래스 하나와
 * {@code @RestControllerAdvice} 하나가 같이 늘었다 — 그 어드바이스들은 로그 문구를 빼면
 * 전부 같은 코드였다. 이번에 auth·academicoperations·member 세 도메인에 코드를 붙이면서
 * 같은 것을 세 벌 더 만들지 않기 위해 상위 타입 하나와 어드바이스 하나로 합쳤다.
 * 새 도메인은 {@link ApiErrorCode} enum만 정의하면 되고 어드바이스는 건드릴 필요가 없다.
 *
 * <p><b>{@code ResponseStatusException}을 상속하지 않는다.</b> 상속하면
 * {@link GlobalExceptionHandler#handleResponseStatus}와 {@link ApiExceptionHandler}가 둘 다
 * 매칭되어 어느 쪽이 잡을지 등록 순서에 의존하게 된다. 별도 타입이면 그런 모호함이 없다.
 */
public class ApiException extends RuntimeException {

	private final ApiErrorCode errorCode;

	/**
	 * 다시 시도할 수 있을 때까지 남은 초. 일시 차단(429)에만 있고 그 외에는 {@code null}이다.
	 * 화면이 남은 시간을 세어 보여 주므로 "언제 다시 되는지"가 답의 일부인 경우가 있다.
	 */
	private final Long retryAfterSeconds;

	public ApiException(ApiErrorCode errorCode) {
		this(errorCode, errorCode.defaultMessage(), null);
	}

	/** 일시 차단용. 남은 시간을 응답과 {@code Retry-After} 헤더에 함께 싣는다. */
	public ApiException(ApiErrorCode errorCode, long retryAfterSeconds) {
		super(errorCode.defaultMessage());
		this.errorCode = errorCode;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public ApiException(ApiErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public ApiException(ApiErrorCode errorCode, Throwable cause) {
		this(errorCode, errorCode.defaultMessage(), cause);
	}

	public ApiException(ApiErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
		this.retryAfterSeconds = null;
	}

	public ApiErrorCode errorCode() {
		return errorCode;
	}

	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
