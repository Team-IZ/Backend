package com.bigproject.backend.domain.assessment.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 검증 세션(assessment_session) 상태. DB CHECK {@code ck_assessment_session_status}와 1:1이다.
 *
 * <h2>19차 R3 — "등"을 지웠다</h2>
 *
 * <p>종전 설명문이 {@code READY · IN_PROGRESS · PAUSED · COMPLETED 등}으로 열려 있어
 * 17차에서 enum 확정을 보류했던 필드다. 확인해 보니 <b>값 집합이 DB CHECK로 이미 닫혀
 * 있었다</b> — 문서만 "등"으로 남아 있었던 것이라 여기서 확정한다.
 *
 * <p>빠져 있던 넷({@link #INTERRUPTED} · {@link #INVALID} · {@link #FAILED} ·
 * {@link #SUPERSEDED})이 모두 <b>비정상 종료</b> 계열이라, "등"으로 접혀 있던 동안 화면이
 * 볼 수 없던 것이 하필 문제 상황이었다.
 *
 * <p>{@link MeasurementAttemptStatus}(응시)와 다른 축이다 — 응시 하나에 세션이 하나 붙지만,
 * 응시는 제출·분석까지 포함하고 세션은 <b>문제를 푸는 구간</b>만 가리킨다.
 */
@Schema(name = "AssessmentSessionStatus",
		description = """
				검증 세션 상태. 응시(`MeasurementAttemptStatus`)와 다른 축이며 **문제를 푸는 구간**만 가리킨다.

				`READY`(시작 전) · `IN_PROGRESS`(진행 중) · `PAUSED`(일시정지) · `COMPLETED`(완료) ·
				`INTERRUPTED`(중단) · `INVALID`(무효) · `FAILED`(실패) · `SUPERSEDED`(다른 세션으로 대체됨)""",
		enumAsRef = true)
public enum AssessmentSessionStatus {

	/** 인트로 동의 전. 아직 시작하지 않았다. */
	READY,

	/** 문제를 풀고 있다. */
	IN_PROGRESS,

	/** 일시정지했다. 이어서 풀 수 있다. */
	PAUSED,

	/** 3개념을 전부 끝냈다. */
	COMPLETED,

	/** 끝내지 못한 채 중단됐다. */
	INTERRUPTED,

	/** 무효 처리됐다. */
	INVALID,

	/** 기술적 실패로 끝났다. */
	FAILED,

	/** 다른 세션이 이 세션을 대체했다. */
	SUPERSEDED
}
