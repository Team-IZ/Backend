package com.bigproject.backend.domain.reporting.domain;

/**
 * DB CHECK: ck_report_generation_item_status — status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')
 *
 * <p>상위 {@link ReportGenerationRunStatus}와 값 집합이 다르다. item에는 {@code PARTIAL}이 없다 —
 * 문제 하나에 대한 AI 호출 1회는 성공이거나 실패이고, "일부 성공"은 <b>문제들 사이</b>에서만
 * 생기는 개념이라 run 쪽에만 있다.
 *
 * <p>AI의 {@code PARTIAL}(서술 생성 실패, 점수만 있음)은 여기서 {@code SUCCEEDED} +
 * {@code narrative_failed=true}로 받는다. 결과가 왔고 토큰도 태웠으므로 실패가 아니다.
 */
public enum ReportGenerationItemStatus {
	QUEUED,
	RUNNING,
	SUCCEEDED,
	FAILED;

	/** 폴링을 계속할 대상인가. */
	public boolean isActive() {
		return this == QUEUED || this == RUNNING;
	}

	public boolean isTerminal() {
		return this == SUCCEEDED || this == FAILED;
	}
}
