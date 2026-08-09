package com.bigproject.backend.domain.projectexecution.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 프로젝트 종류.
 *
 * <p><b>공유 스키마로 낸다.</b> 전에는 {@code CreateProjectRequest.category}와
 * {@code ProjectResponse.category}에 값 목록이 각각 복사돼 나갔다. 요청과 응답이 같은 개념인데
 * 생성된 프론트 타입에서는 서로 다른 타입이 되어, 폼에서 고른 값을 응답 타입을 받는 함수에 그대로
 * 넘길 수 없었다. 더 나쁜 것은 한쪽에만 값이 늘어도 아무도 눈치채지 못한다는 점이다.
 */
@Schema(
        name = "ProjectCategory",
        description = "프로젝트 종류. MINI_PROJECT(미니프로젝트 — 교안 연결·회차의 대상) · BIG_PROJECT(빅프로젝트 — 교안을 연결하지 않는다)",
        enumAsRef = true
)
public enum ProjectCategory {
    MINI_PROJECT,
    BIG_PROJECT
}