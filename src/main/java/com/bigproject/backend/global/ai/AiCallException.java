package com.bigproject.backend.global.ai;

import org.springframework.http.HttpStatusCode;

/**
 * AI(FastAPI) 호출 실패. <b>업무 실패가 아니라 연동 실패다.</b>
 *
 * <p>도메인 예외({@code ReportException} 등)와 섞지 않는 이유: 리포트가 없다는 것과
 * 리포트를 만들 서버에 못 닿았다는 것은 운영자가 취할 조치가 다르다. 전자는 회차를 더 돌려야
 * 하고 후자는 AI 서버를 봐야 한다.
 *
 * <p>{@code retryable}은 호출부가 재시도 여부를 판단하는 근거다. AI 명세 §5.2의 8종
 * {@code failureCode} 중 전송 계층 실패(TIMEOUT·RATE_LIMITED·PROVIDER_ERROR)는 재시도 가치가
 * 있고, 계약 위반(INVALID_JSON)이나 멱등 충돌(409)은 다시 불러도 같은 결과다.
 */
public class AiCallException extends RuntimeException {

	private final HttpStatusCode status;
	private final String failureCode;
	private final boolean retryable;

	public AiCallException(HttpStatusCode status, String failureCode, boolean retryable, String message) {
		super(message);
		this.status = status;
		this.failureCode = failureCode;
		this.retryable = retryable;
	}

	public AiCallException(HttpStatusCode status, String failureCode, boolean retryable, String message,
			Throwable cause) {
		super(message, cause);
		this.status = status;
		this.failureCode = failureCode;
		this.retryable = retryable;
	}

	/** AI가 돌려준 HTTP 상태. 네트워크 단계에서 끊겼으면 null이다. */
	public HttpStatusCode status() {
		return status;
	}

	/** AI 명세 §5.2의 failureCode. 전송 자체가 실패했으면 null이다. */
	public String failureCode() {
		return failureCode;
	}

	public boolean retryable() {
		return retryable;
	}
}
