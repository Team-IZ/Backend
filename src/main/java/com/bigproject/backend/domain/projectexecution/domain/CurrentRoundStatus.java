package com.bigproject.backend.domain.projectexecution.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code trainee_home_round_view.representative_status} 값을 그대로 옮긴 것.
 *
 * <p>상태 우선순위(다시 보기 > 응시 완료 > 응시 창 마감 > 응시 중 > 응시 가능 > 분석 실패 >
 * 제출 마감 지남 > 미제출 > 분석 중)는 뷰 정의 SQL의 CASE 순서가 계약이다 — 이 enum이나
 * 서비스 코드에서 다시 판정하지 않는다. 뷰가 원천이고 여기는 옮겨 담기만 한다.
 *
 * <p>{@link #NO_ACTIVE_ROUND}만 예외로, 뷰에 해당 회차 행 자체가 없을 때(OPEN 회차 없음)
 * 애플리케이션 레이어에서 붙이는 값이다.
 */
@Schema(name = "CurrentRoundStatus", description = """
		교육생 홈이 지금 보여줘야 할 상태 하나. trainee_home_round_view.representative_status 원문.
		- SUBMISSION_REQUIRED: 미제출(기한 내)
		- SUBMISSION_MISSED: 제출 마감 지남
		- ANALYZING: 제출 완료·분석 중
		- ANALYSIS_FAILED: 분석 실패
		- ASSESSMENT_AVAILABLE: 응시 가능(창이 열린 동안)
		- ASSESSMENT_IN_PROGRESS: 응시 진행 중(세션 이어하기)
		- ASSESSMENT_WINDOW_CLOSED: 응시 창 마감(미응시·중단 포함)
		- ASSESSMENT_COMPLETED: 응시 완료(리포트 대기 또는 열람 가능)
		- REVIEW_REQUIRED: 다시 보기 대상
		- NO_ACTIVE_ROUND: 진행 중인 회차 없음(빈 상태, 뷰가 아니라 애플리케이션이 판정)
		""", enumAsRef = true)
public enum CurrentRoundStatus {
    SUBMISSION_REQUIRED,
    SUBMISSION_MISSED,
    ANALYZING,
    ANALYSIS_FAILED,
    ASSESSMENT_AVAILABLE,
    ASSESSMENT_IN_PROGRESS,
    ASSESSMENT_WINDOW_CLOSED,
    ASSESSMENT_COMPLETED,
    REVIEW_REQUIRED,
    NO_ACTIVE_ROUND
}
