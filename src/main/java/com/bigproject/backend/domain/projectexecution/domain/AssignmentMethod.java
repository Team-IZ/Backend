package com.bigproject.backend.domain.projectexecution.domain;

/**
 * 팀 배정 방식. DB CHECK(ck_team_membership_assignment_method)가 이 셋만 허용한다(확인 완료).
 * AUTO(자동 배분) · MANUAL(매니저가 손으로 배정) · TRANSFER(제출 시작 후 팀 이동 — 정의 문서 ⑤
 * "제출 시작됨: [팀 이동]만 남는다"가 이 값이다).
 */
public enum AssignmentMethod {
    AUTO,
    MANUAL,
    TRANSFER
}