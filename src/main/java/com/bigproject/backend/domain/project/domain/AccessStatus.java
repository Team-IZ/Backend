package com.bigproject.backend.domain.project.domain;

// 저장소 접근 확인 결과. 연결 직후엔 PENDING이고, 확인 후 ACCESSIBLE 또는 DENIED로 바뀜
public enum AccessStatus {
    PENDING,
    ACCESSIBLE,
    DENIED
}