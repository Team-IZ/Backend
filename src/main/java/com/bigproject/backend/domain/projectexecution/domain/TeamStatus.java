package com.bigproject.backend.domain.projectexecution.domain;

// 팀 상태. 반 해체 후에도 과거 팀 소속 기록(repository, requirement_assessment)이
// 이 team_id를 FK로 참조하므로 물리 삭제 대신 상태로만 관리한다
public enum TeamStatus {
    ACTIVE,
    DISBANDED
}
