package com.bigproject.backend.domain.curriculum.domain;

import com.bigproject.backend.global.exception.ApiException;

/**
 * 교안 도메인 예외.
 *
 * <p><b>{@link ApiException}을 상속한다.</b> 전에는 {@code RuntimeException}만 상속해서 이 예외를
 * 잡는 어드바이스가 없었고, {@link CurriculumErrorCode}에 적어 둔 상태와 문구가 응답에 실리지 못한 채
 * 전부 500으로 나갔다. 상위 타입을 바꾸면 {@code ApiExceptionHandler} 하나가 받아 {@code code}와
 * 상태를 그대로 내려보낸다 — 도메인마다 어드바이스를 한 벌씩 더 만들지 않는다는 {@link ApiException}의
 * 방침 그대로다.
 *
 * <p>{@code errorCode()}의 반환 타입만 좁혀 둔다. 기존 호출부가 {@link CurriculumErrorCode}로
 * 그대로 받고 있어서다.
 */
public class CurriculumException extends ApiException {

    public CurriculumException(CurriculumErrorCode errorCode) {
        super(errorCode);
    }

    public CurriculumException(CurriculumErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public CurriculumErrorCode errorCode() {
        return (CurriculumErrorCode) super.errorCode();
    }
}