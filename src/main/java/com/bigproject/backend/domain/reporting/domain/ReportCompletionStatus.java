package com.bigproject.backend.domain.reporting.domain;

/**
 * report_snapshot.completion_status. DB CHECK(ck_report_snapshot_completion_status)와 1:1이다.
 *
 * <p>{@link #PARTIAL}은 실패가 아니라 <b>일부가 빈 채로 확정됐다</b>는 뜻이다.
 * {@code missing_count}가 몇 건이 빠졌는지 들고 있다.
 *
 * <p>화면에서 PARTIAL을 0으로 그리면 안 된다 — "안 한 것"과 "못 읽은 것"은 다르다.
 * Usage Metering의 {@code costComplete=false} 처리와 같은 원칙이다.
 */
public enum ReportCompletionStatus {

	/** 대상 전원의 결과가 들어 있다. */
	FULL,

	/** 일부가 빠진 채 확정됐다. missingCount만큼 모수에서 제외된 상태다. */
	PARTIAL
}
