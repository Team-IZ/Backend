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

	/**
	 * AI 서버에 연결 자체가 안 됐는가(연결 거부·호스트 해석 실패). {@code false}면 연결은 됐지만
	 * 실패한 경우(응답 에러·읽기 타임아웃 등)다.
	 *
	 * <p>{@link AnalysisBatchService}가 배치 단위로 "AI가 완전히 죽었는가"를 판단하는 유일한
	 * 신호다({@code AiClient}의 {@code CONNECTION_REFUSED} failureCode에서 옮겨 온다) — DB에는
	 * 남기지 않는다. 기록되는 {@link #failureCode}는 그대로 {@code TEMPORARY_ERROR}다
	 * (D1, 새 DB enum 값을 추가하지 않기로 한 결정 참고).
	 */
	private final boolean connectionLevel;

	public AnalysisServerException(AnalysisFailureCode failureCode, String message) {
		this(failureCode, message, null, false);
	}

	public AnalysisServerException(AnalysisFailureCode failureCode, String message, Throwable cause) {
		this(failureCode, message, cause, false);
	}

	public AnalysisServerException(AnalysisFailureCode failureCode, String message, Throwable cause,
			boolean connectionLevel) {
		super(message, cause);
		this.failureCode = failureCode;
		this.connectionLevel = connectionLevel;
	}

	public AnalysisFailureCode failureCode() {
		return failureCode;
	}

	public boolean isConnectionLevel() {
		return connectionLevel;
	}
}
