package com.bigproject.backend.domain.manager.domain;

// 선택 회차에 대한 교육생 개인 행의 결과 상태. measurement_attempt.status/validity_review_status에서 직접 파생하며
// 결과 없음·무효·진행 중을 0점이나 완료로 치환하지 않는다.
public enum TraineeRoundRowResultStatus {
	NO_ATTEMPT,
	NOT_STARTED,
	SUBMITTED,
	ANALYZING,
	SESSION_READY,
	SESSION_IN_PROGRESS,
	COMPLETED,
	FAILED,
	EXPIRED,
	INVALID
}
