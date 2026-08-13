package com.bigproject.backend.domain.evaluation.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/** 결과 탭 조회의 에러 코드. 프론트가 메시지가 아니라 <b>코드</b>로 분기한다. */
public enum EvaluationErrorCode implements ApiErrorCode {

	/** 프로젝트가 없거나, 삭제됐거나, 그 번호의 회차가 없다. 셋을 구분하지 않는다. */
	PROJECT_ROUND_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트 회차를 찾을 수 없습니다."),

	/**
	 * 그 회차의 대상이 아니거나 <b>담당 반 밖의 교육생</b>이다. 둘을 구분하지 않는다 —
	 * 구분해 주면 남의 반에 누가 있는지 확인할 수 있다.
	 */
	EVALUATION_TRAINEE_NOT_FOUND(HttpStatus.NOT_FOUND, "담당 범위에서 교육생을 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	EvaluationErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	@Override
	public HttpStatus status() {
		return status;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}
}
