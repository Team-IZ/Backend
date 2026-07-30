package com.bigproject.backend.domain.project.domain;

// 저장소 자체의 상태. 팀이 레포를 바꾸거나 제거하면 REMOVED로 전환
public enum RepositoryStatus {
    ACTIVE,
    REMOVED
}