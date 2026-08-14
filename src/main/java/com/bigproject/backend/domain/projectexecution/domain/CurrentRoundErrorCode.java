package com.bigproject.backend.domain.projectexecution.domain;

import com.bigproject.backend.global.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * TR-01(교육생 홈) "지금 할 일 하나" 조회가 내려보내는 에러 코드.
 *
 * <p>회차가 하나도 없는 것("진행 중인 회차 없음")은 에러가 아니라 정상적인 빈 상태라
 * {@link CurrentRoundStatus#NO_ACTIVE_ROUND}를 담은 200으로 내려간다 — 여기 코드로 추가하지 않는다.
 */
public enum CurrentRoundErrorCode implements ApiErrorCode {

    /** 교육생이 아닌 계정(오퍼레이터·매니저)이 이 엔드포인트를 호출했다. */
    NOT_A_TRAINEE(HttpStatus.FORBIDDEN, "교육생 계정만 조회할 수 있습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    CurrentRoundErrorCode(HttpStatus status, String defaultMessage) {
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