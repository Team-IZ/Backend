package projectexecution.domain;

// 팀 배정 방식. 매니저가 수동으로 배정했는지, 실력 균형 맞춰 자동 배정했는지,
// 무작위 배정했는지를 구분 — 나중에 "왜 이 조합이 됐는지" 추적할 때 필요
public enum AssignmentMethod {
    MANUAL,
    BALANCED,
    RANDOM
}
