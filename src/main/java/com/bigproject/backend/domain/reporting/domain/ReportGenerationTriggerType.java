package com.bigproject.backend.domain.reporting.domain;

/**
 * {@code report_generation_run.trigger_type}.
 *
 * <p>⚠️ 이 컬럼에는 <b>DDL CHECK가 없다.</b> 그래서 값 집합의 근거는 시드 실사용값이다 —
 * {@code docs/dummy-data/seed-all-tables.sql}의 report_generation_run 4행이 {@code SCHEDULED}와
 * {@code USER_REQUESTED} 둘만 쓴다. 추측으로 값을 늘리면 나중에 CHECK가 생길 때 어긋난다.
 *
 * <p>지금 생성 경로가 있는 것은 {@link #SCHEDULED}뿐이다. {@link #USER_REQUESTED}는 운영자
 * 수동 재생성용이고 그 API는 아직 없다.
 */
public enum ReportGenerationTriggerType {

	/** 회차 종료 후 자동 생성 배치. */
	SCHEDULED,

	/** 운영자가 화면에서 재생성을 누른 경우. 진입점 미구현. */
	USER_REQUESTED
}
