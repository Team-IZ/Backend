package com.bigproject.backend.domain.projectexecution.domain;

/**
 * 팀 편성 상태. DB CHECK(ck_team_status)가 이 둘만 허용한다(실제 DDL로 확인 완료).
 * 화면의 5단계는 이 값 + 미배정 인원 유무 + 제출 존재 유무의 조합이다 — TeamStatus 단독으론
 * "편성 중"과 "전원 배정됨"을, "확정됨"과 "제출 시작됨"을 구분하지 못한다.
 */
public enum TeamStatus {
    /** 편성 전·편성 중·전원 배정됨 — 자유롭게 팀원을 넣고 뺄 수 있다. */
    DRAFT,
    /** 확정됨. 제출 전이면 되돌릴 수 있고(reopen), 제출이 시작되면 서비스 계층이 되돌리기를 막는다. */
    CONFIRMED
}
