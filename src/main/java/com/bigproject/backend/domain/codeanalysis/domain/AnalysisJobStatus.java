package com.bigproject.backend.domain.codeanalysis.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DB CHECK: ck_analysis_job_status — status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED')
 *
 * <p>{@code PARTIAL}은 일부 개념만 문제 생성에 성공한 경우다. FAILED와 구분해야 교육생에게
 * "일부 결과 있음"으로 안내할 수 있다.
 */
@Schema(name = "AnalysisJobStatus",
		description = "코드 분석 작업 상태. QUEUED · RUNNING · SUCCEEDED · PARTIAL(일부 개념만 생성) · FAILED",
		enumAsRef = true)
public enum AnalysisJobStatus {
	QUEUED,
	RUNNING,
	SUCCEEDED,
	PARTIAL,
	FAILED
}
