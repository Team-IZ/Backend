package com.bigproject.backend.domain.curriculum.domain;

import org.springframework.http.HttpStatus;

public enum CurriculumErrorCode {

    /** project 도메인의 CURRICULUM_NOT_REGISTERED와 대응 — 이 도메인 안에서 버전을 못 찾았을 때. */
    CURRICULUM_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 교안입니다."),
    CURRICULUM_MATERIAL_NOT_FOUND(HttpStatus.NOT_FOUND, "교안을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    CurriculumErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}