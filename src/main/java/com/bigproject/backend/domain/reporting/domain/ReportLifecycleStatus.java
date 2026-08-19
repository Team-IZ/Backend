package com.bigproject.backend.domain.reporting.domain;

/**
 * report.lifecycle_status. DB CHECK(ck_report_lifecycle_status)와 1:1이다.
 *
 * <p>이건 <b>리포트 행 자체의 수명</b>이지 교육생에게 보이는지 여부가 아니다.
 * 열람 가능 여부는 {@code published_at}이 답한다 — {@code ACTIVE}인데 아직 발행 전인 리포트가
 * 있고(생성은 끝났지만 발행 예정 시각 전), 그때 화면은 `리포트 생성 중`으로 그린다.
 *
 * <p>{@code SUPERSEDED}가 이 둘을 나눠야 하는 이유이기도 하다 — 재생성으로 대체된 리포트는
 * 발행 시각이 남아 있어도 학생이 볼 것이 아니다({@code Report#isVisibleToTrainee}는 둘 다 본다).
 */
public enum ReportLifecycleStatus {

	/** 생성 중이거나 생성 실패. 어느 화면에도 나가지 않는다. */
	DRAFT,

	/** 현재 유효한 리포트. 화면이 읽는 것은 이 상태뿐이다. */
	ACTIVE,

	/** 재생성으로 대체됐다. 이력으로만 남고 조회 대상이 아니다. */
	SUPERSEDED
}
