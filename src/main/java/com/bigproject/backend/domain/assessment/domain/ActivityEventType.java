package com.bigproject.backend.domain.assessment.domain;

/**
 * {@code problem_stage_activity_log.event_type}. 이름을 DB CHECK 제약
 * ({@code ck_problem_stage_activity_log_event_type})의 허용값과 그대로 맞춰, SQL에 넘길 때
 * 별도 매핑 없이 {@link #name()}을 바로 쓴다.
 */
public enum ActivityEventType {
	WINDOW_LEAVE,
	CONNECTION_LOSS,
	FIRST_KEYSTROKE_DELAY
}
