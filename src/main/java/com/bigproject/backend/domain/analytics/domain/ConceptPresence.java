package com.bigproject.backend.domain.analytics.domain;

/**
 * 한 기수에서 검증 개념이 비교 가능한 형태로 존재하는지 나타낸다.
 * 값이 없는 칸(그 기수에 없던 개념)과 평균 0단을 화면이 같은 색으로 읽으면 안 되므로 분리한다.
 */
public enum ConceptPresence {
	// 그 기수의 발행 스냅샷에 개념이 있고 평균을 읽을 수 있음
	PRESENT,
	// 그 기수에는 없던 개념 (목업의 '없던 개념' 빗금 칸)
	ABSENT_IN_COHORT,
	// teaches.status='MERGED'. 다른 개념으로 병합돼 두 기수의 뜻이 같다고 볼 수 없음
	MERGED
}
