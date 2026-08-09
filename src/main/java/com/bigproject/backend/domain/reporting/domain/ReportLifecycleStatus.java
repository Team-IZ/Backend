package com.bigproject.backend.domain.reporting.domain;

/**
 * report.lifecycle_status. DB CHECK(ck_report_lifecycle_status)와 1:1이다.
 *
 * <p>이건 <b>리포트 행 자체의 수명</b>이지 교육생에게 보이는지 여부가 아니다.
 * 공개 여부는 {@link TraineeReleaseStatus}가 따로 들고 있다 — 둘을 섞으면
 * "발행됐지만 아직 공개 범위를 못 정한" 상태(TR-04 `PENDING_VISIBILITY`)를 표현할 수 없다.
 */
public enum ReportLifecycleStatus {

	/** 생성 중이거나 생성 실패. 어느 화면에도 나가지 않는다. */
	DRAFT,

	/** 현재 유효한 리포트. 화면이 읽는 것은 이 상태뿐이다. */
	ACTIVE,

	/** 재생성으로 대체됐다. 이력으로만 남고 조회 대상이 아니다. */
	SUPERSEDED
}
