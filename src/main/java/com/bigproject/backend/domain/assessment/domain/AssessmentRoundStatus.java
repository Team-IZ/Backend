package com.bigproject.backend.domain.assessment.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 이해도 확인 회차의 진행 상태. {@code project_assessment_round.status}다.
 *
 * <p><b>{@code ProjectStatus}(프로젝트 상태)와 값이 겹치지만 다른 개념이다.</b> 그쪽은 3값
 * ({@code PLANNED}·{@code RUNNING}·{@code CLOSED})이고 이쪽은 4값이다 — 회차는 응시 창이 닫힌 뒤
 * ({@code CLOSED}) 집계가 끝나 완결되는 단계({@code COMPLETED})가 하나 더 있다.
 *
 * <p>19차 R2로 공용 스키마가 됐다 — {@code CurrentRoundResponse}와 {@code UpcomingRoundResponse}가
 * 같은 값 집합을 각자 인라인으로 갖고 있었다.
 */
@Schema(name = "AssessmentRoundStatus",
		description = """
				이해도 확인 회차 상태. `PLANNED`(예정) · `OPEN`(진행 중) · `CLOSED`(마감) · `COMPLETED`(완료).

				⚠️ 프로젝트 상태(`ProjectStatus`)와 값이 겹치지만 다른 개념이다 — 그쪽은 3값이다.""",
		enumAsRef = true)
public enum AssessmentRoundStatus {

	/**
	 * 예정. 이 상태에서만 응시 창 일정이 비어 있을 수 있다
	 * ({@code ck_project_assessment_round_assessment_window_required}가 PLANNED만 면제한다).
	 */
	PLANNED,

	/** 진행 중. */
	OPEN,

	/** 마감. */
	CLOSED,

	/** 완료. */
	COMPLETED
}
