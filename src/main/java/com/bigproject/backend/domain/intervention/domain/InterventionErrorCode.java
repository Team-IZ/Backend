package com.bigproject.backend.domain.intervention.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

public enum InterventionErrorCode implements ApiErrorCode {

	/**
	 * 담당 반이 아니거나 존재하지 않는다. <b>둘을 구분하지 않는다</b> — 담당 밖 케이스에
	 * 403을 주면 "그 회차에 그런 교육생이 있다"는 사실이 새어 나간다.
	 */
	INTERVIEW_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "담당 범위에서 면담 대상을 찾을 수 없습니다."),

	/**
	 * 면담이 이미 시작·종결됐다. {@code interview_candidate} COMMENT가 제외를
	 * "연결된 면담이 PENDING인 후보"로 한정한다 — 이미 만난 사람을 이번 회차 대상에서
	 * 빼는 것은 상태 모델상 의미가 없다.
	 */
	INTERVIEW_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작되었거나 종결된 면담은 제외할 수 없습니다."),

	/** 제외 상태가 아닌데 되돌리기를 호출했다(또는 그 반대). */
	INTERVIEW_EXCLUSION_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),

	/** 다른 요청이 먼저 상태를 바꿨다. {@code row_version} 재검증 실패. */
	INTERVIEW_ROW_VERSION_CONFLICT(HttpStatus.CONFLICT, "면담 대상 상태가 변경되었습니다. 다시 조회해 주세요."),

	/**
	 * 아직 브리프를 만들지 않았다. <b>조회가 만들지 않는다</b> — 생성은 쓰기가 세 겹으로
	 * 일어나는 동작이라 별도 {@code POST}가 갖는다. 화면은 이 응답을 받으면
	 * {@code [브리프 생성]} 버튼을 그린다.
	 */
	INTERVIEW_BRIEF_NOT_CREATED(HttpStatus.NOT_FOUND, "아직 생성되지 않은 브리프입니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	InterventionErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override public HttpStatus status() { return status; }
	@Override public String defaultMessage() { return defaultMessage; }
}
