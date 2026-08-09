package com.bigproject.backend.domain.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * report.trainee_release_status — 교육생에게 리포트를 열어 줬는지.
 * DB CHECK(ck_report_trainee_release_status)와 1:1이다.
 *
 * <p>DB CHECK(ck_report_trainee_release_status_2)가 이 값과 나머지 3개 컬럼의 조합을
 * 강제한다. 어겨서 저장하면 제약 위반으로 실패하므로 반드시 함께 움직여야 한다.
 *
 * <pre>
 * NOT_CONFIGURED → scope=NULL      · releasedAt=NULL      · releasedBy=NULL
 * WITHHELD       → scope=PRIVATE   · releasedAt=NULL      · releasedBy=NULL
 * RELEASED       → scope=SUMMARY|FULL · releasedAt≠NULL · releasedBy≠NULL
 * </pre>
 *
 * <p>TR-04의 화면 상태와는 이렇게 대응한다.
 * {@link #NOT_CONFIGURED} → `PENDING_VISIBILITY`(공개 범위 미지정),
 * {@link #WITHHELD} → 목록에는 회차가 보이되 본문이 잠긴다,
 * {@link #RELEASED} → `PUBLISHED`.
 */
@Schema(name = "TraineeReleaseStatus", description = "교육생 리포트 공개 상태. NOT_CONFIGURED(공개 범위 미지정) · WITHHELD(비공개) · RELEASED(공개)", enumAsRef = true)
public enum TraineeReleaseStatus {

	/** 공개 범위를 아직 정하지 않았다. TR-04 `PENDING_VISIBILITY`. */
	NOT_CONFIGURED,

	/** 비공개로 정했다. scope는 반드시 PRIVATE이다. */
	WITHHELD,

	/** 공개했다. scope는 SUMMARY 또는 FULL이고 공개 시각·주체가 반드시 있다. */
	RELEASED
}
