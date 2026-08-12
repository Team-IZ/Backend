package com.bigproject.backend.domain.codeanalysis.domain;

/**
 * DB CHECK: ck_analysis_job_status — status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED')
 *
 * <p>{@code PARTIAL}은 일부 개념만 문제 생성에 성공한 경우다. FAILED와 구분해야 교육생에게
 * "일부 결과 있음"으로 안내할 수 있다.
 */
public enum AnalysisJobStatus {
	QUEUED,
	RUNNING,
	SUCCEEDED,
	PARTIAL,
	FAILED
}
