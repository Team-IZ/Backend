package com.bigproject.backend.domain.projectexecution.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 프로젝트 진행 상태.
 *
 * <p><b>값이 {@code CohortStatus}와 같지만 일부러 합치지 않는다.</b> 8차 요청에서 "{@code $ref}로
 * 바꾸면 된다"는 선택지를 받았고 실제로 지금은 세 값이 정확히 겹친다. 그럼에도 나누어 두는 쪽을 골랐다 —
 * <em>기수</em>가 열렸다/돌아간다/끝났다와 <em>프로젝트</em>가 그렇다는 것은 독립적으로 움직이는
 * 개념이고(진행 중 기수 안에 개설 예정 프로젝트가 있다), 한쪽에만 값이 붙는 날
 * ({@code ARCHIVED}·{@code CANCELLED} 같은) 공유 스키마였다면 다른 쪽 화면의 분기가 조용히 넓어진다.
 * 값이 같다는 것은 우연이지 같은 개념이라는 뜻이 아니다.
 *
 * <p>대신 이 enum 자체를 {@code ProjectStatus}라는 <b>이름 있는 공유 스키마</b>로 낸다. 값 목록이
 * DTO마다 복사되던 문제(8차 §4의 실제 지적)는 그것으로 사라지고, 두 개념을 잘못 묶는 위험은 피한다.
 */
@Schema(
        name = "ProjectStatus",
        description = "프로젝트 진행 상태. PLANNED(생성됨 — 아직 시작 전) · RUNNING(진행 중) · CLOSED(종료). 값은 CohortStatus와 같지만 개념이 달라 별도 스키마로 둔다.",
        enumAsRef = true
)
public enum ProjectLifecycleStatus {
    PLANNED,
    RUNNING,
    CLOSED
}