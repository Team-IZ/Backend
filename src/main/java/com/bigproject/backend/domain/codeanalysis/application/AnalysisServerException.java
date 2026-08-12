package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;

/**
 * AI 서버 호출이 실패했을 때 던진다.
 *
 * <p>{@link #failureCode}를 함께 갖는 이유는 이 실패가 곧 {@code analysis_job.failure_code}에 기록되기
 * 때문이다. {@code ck_analysis_job_failure_code}가 FAILED일 때 코드를 NOT NULL로 요구하므로, 예외에
 * 코드가 없으면 실패를 기록할 방법이 없다.
 */
public class AnalysisServerException extends RuntimeException {

	private final transient AnalysisFailureCode failureCode;

	public AnalysisServerException(AnalysisFailureCode failureCode, String message) {
		super(message);
		this.failureCode = failureCode;
	}

	public AnalysisServerException(AnalysisFailureCode failureCode, String message, Throwable cause) {
		super(message, cause);
		this.failureCode = failureCode;
	}

	public AnalysisFailureCode failureCode() {
		return failureCode;
	}
}
