package com.bigproject.backend.domain.reporting.domain;

/**
 * DB CHECK: ck_report_generation_run_status —
 * status IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','RETRYING')
 *
 * <p>{@code PARTIAL}은 문제 3건 중 일부만 성공했거나 {@code narrativeFailed=true}가 섞인 경우다
 * (report_generation_item 테이블 코멘트). {@code FAILED}로 뭉뚱그리면 나머지 문제의 결과가
 * 있는데도 리포트를 통째로 못 쓰는 것으로 보인다.
 *
 * <p>{@code RETRYING}은 값만 유지한다. 재시도는 기존 행을 되돌리지 않고 {@code execution_no+1}의
 * 새 행으로 남기므로(analysis_job과 같은 규칙) 이 상태로 전이하는 경로가 없다.
 */
public enum ReportGenerationRunStatus {
	QUEUED,
	RUNNING,
	COMPLETED,
	PARTIAL,
	FAILED,
	RETRYING;

	/** 더 기다려도 바뀌지 않는 상태인가. */
	public boolean isTerminal() {
		return this == COMPLETED || this == PARTIAL || this == FAILED;
	}
}
