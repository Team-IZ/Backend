package com.bigproject.backend.domain.assessment.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 교육생 홈 카드의 기본 버튼. {@code trainee_home_round_view.default_action_code} 계약값이다.
 *
 * <p>{@link TraineeRepresentativeStatus}와 짝이다 — 배지가 "지금 무슨 상태인가"이고 이쪽이
 * "그래서 무엇을 누르나"다. 둘 다 View가 정하므로 화면이 배지에서 버튼을 유추하지 않는다.
 */
@Schema(name = "TraineeDefaultActionCode",
		description = """
				교육생 홈 카드의 기본 버튼.

				`VIEW_REPORT`(리포트 보기) · `WAIT_FOR_REPORT`(발행 대기) · `START_REVIEW`(다시 보기 시작) ·
				`RESUME_ASSESSMENT`(응시 이어하기) · `START_ASSESSMENT`(응시 시작) ·
				`RESUBMIT_REPOSITORY`(저장소 재제출) · `RESUBMIT_ZIP`(ZIP 재업로드) ·
				`CONTACT_MANAGER`(매니저 문의 — 복구 경로 없음) · `SUBMIT_CODE`(코드 제출) ·
				`WAIT_FOR_ANALYSIS`(분석 대기) · `NONE`(할 일 없음)""",
		enumAsRef = true)
public enum TraineeDefaultActionCode {
	VIEW_REPORT,
	WAIT_FOR_REPORT,
	START_REVIEW,

	/** 응시 이어하기. {@code ASSESSMENT_IN_PROGRESS}와 짝이다. */
	RESUME_ASSESSMENT,

	START_ASSESSMENT,

	/** 분석 실패 + 마감 전. */
	RESUBMIT_REPOSITORY,

	/** 분석 실패 + 마감 전. */
	RESUBMIT_ZIP,

	/** 복구 경로가 없다. */
	CONTACT_MANAGER,

	SUBMIT_CODE,
	WAIT_FOR_ANALYSIS,
	NONE
}
