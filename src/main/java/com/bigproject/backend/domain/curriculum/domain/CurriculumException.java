package com.bigproject.backend.domain.curriculum.domain;

public class CurriculumException extends RuntimeException {

    private final CurriculumErrorCode errorCode;

    public CurriculumException(CurriculumErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public CurriculumException(CurriculumErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CurriculumErrorCode errorCode() {
        return errorCode;
    }
}