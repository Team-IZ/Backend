package com.bigproject.backend.domain.projectexecution.domain;

// PLANNED: 회차 예정 / OPEN: 응시 가능 / CLOSED: 응시 마감 / COMPLETED: 후속 처리 완료
public enum CheckpointStatus {
    PLANNED,
    OPEN,
    CLOSED,
    COMPLETED
}