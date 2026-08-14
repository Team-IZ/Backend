package com.bigproject.backend.domain.projectexecution.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code trainee_home_round_view.default_action_code} 값을 그대로 옮긴 것.
 * "지금 눌러야 할 버튼"을 뷰가 직접 계산해 준다 — status만으로는 갈리지 않는 경우
 * (예: ASSESSMENT_COMPLETED인데 리포트가 발행됐는지 여부)까지 반영돼 있다.
 */
@Schema(name = "DefaultActionCode", description = """
		지금 화면에 보여줄 기본 액션 버튼.
		VIEW_REPORT · WAIT_FOR_REPORT · START_REVIEW · RESUME_ASSESSMENT · START_ASSESSMENT ·
		RESUBMIT_ZIP · RESUBMIT_REPOSITORY · CONTACT_MANAGER · SUBMIT_CODE · WAIT_FOR_ANALYSIS · NONE
		""", enumAsRef = true)
public enum DefaultActionCode {
    VIEW_REPORT,
    WAIT_FOR_REPORT,
    START_REVIEW,
    RESUME_ASSESSMENT,
    START_ASSESSMENT,
    RESUBMIT_ZIP,
    RESUBMIT_REPOSITORY,
    CONTACT_MANAGER,
    SUBMIT_CODE,
    WAIT_FOR_ANALYSIS,
    NONE
}
