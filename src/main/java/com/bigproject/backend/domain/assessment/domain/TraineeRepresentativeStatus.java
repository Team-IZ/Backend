package com.bigproject.backend.domain.assessment.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 교육생 홈 카드의 대표 상태. {@code trainee_home_round_view.representative_status}가 계산한 계약값이다.
 *
 * <p><b>서버가 다시 파생시키지 않는다.</b> View의 CASE가 위에서부터 먼저 맞는 것 하나로 정하고
 * 서버는 그 값을 그대로 낸다 — 판정을 서버가 다시 하면 규칙이 두 벌이 된다.
 *
 * <p>{@link #NO_ACTIVE_ROUND}만 예외로 <b>서버가 만든다.</b> 진행 중인 프로젝트가 없으면 View가
 * 0행을 반환하므로 View는 이 상태를 표현할 수 없고, 합성 카드에서만 쓰인다.
 *
 * <p>19차 R2로 공용 스키마가 됐다 — {@code CurrentRoundResponse}와 {@code PastRoundResponse}가
 * 같은 값 집합을 각자 인라인으로 갖고 있었다.
 */
@Schema(name = "TraineeRepresentativeStatus",
		description = """
				교육생 홈 카드의 대표 상태(배지). 위에서부터 먼저 맞는 것 하나로 정해진다.

				`REVIEW_REQUIRED`(다시 보기 미완료) · `ASSESSMENT_COMPLETED`(응시 완료) ·
				`ASSESSMENT_WINDOW_CLOSED`(응시 창 마감) · `ASSESSMENT_IN_PROGRESS`(응시 중·일시정지) ·
				`ASSESSMENT_AVAILABLE`(응시 가능) · `ANALYSIS_FAILED`(분석 실패) ·
				`SUBMISSION_MISSED`(마감까지 미제출) · `SUBMISSION_REQUIRED`(미제출·마감 전) ·
				`ANALYZING`(제출 후 분석 중) · `NO_ACTIVE_ROUND`(진행 회차 없음 — 서버 합성 카드 전용)""",
		enumAsRef = true)
public enum TraineeRepresentativeStatus {

	/** 다시 보기 배정이 있고 아직 끝나지 않았다. */
	REVIEW_REQUIRED,

	/** 응시를 완료했다. */
	ASSESSMENT_COMPLETED,

	/** 미응시·중단이거나 개인 응시 창이 닫혔다. */
	ASSESSMENT_WINDOW_CLOSED,

	/**
	 * 세션을 풀고 있거나 일시정지했다.
	 *
	 * <p>⚠️ 이 값을 {@link #ASSESSMENT_AVAILABLE}로 접으면 안 된다 — 화면이 `응시 시작`을 그리는데
	 * 실제로는 <b>이어하기</b>다. 이때 {@code defaultActionCode}가 {@code RESUME_ASSESSMENT}로 온다.
	 */
	ASSESSMENT_IN_PROGRESS,

	/** 응시할 수 있다. */
	ASSESSMENT_AVAILABLE,

	/** 분석이 실패했다. */
	ANALYSIS_FAILED,

	/** 마감까지 제출하지 않았다. */
	SUBMISSION_MISSED,

	/** 아직 제출하지 않았다(마감 전). */
	SUBMISSION_REQUIRED,

	/** 위 어디에도 해당하지 않는다(제출 후 분석 중). */
	ANALYZING,

	/** 진행 회차가 없다. <b>서버 합성 카드 전용</b>이며 View는 이 값을 만들지 못한다. */
	NO_ACTIVE_ROUND
}
