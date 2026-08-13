package com.bigproject.backend.domain.assessment.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 응시(measurement_attempt) 진행 상태. DB CHECK {@code ck_measurement_attempt_status}와 1:1이다.
 *
 * <h2>한 상태 기계를 두 필드가 쓴다</h2>
 *
 * <p>{@code initialAttemptStatus}(첫 응시)와 {@code reviewStatus}(다시 보기)가 <b>같은 컬럼의
 * 같은 값 집합</b>이다. 다시 보기 전용 상태 기계가 따로 있는 것이 아니라
 * {@code attempt_type}만 {@code INITIAL}/{@code REVIEW}로 다를 뿐이다.
 *
 * <p>View가 각각 {@code primary_attempt_status} · {@code latest_review_status}로 옮겨 담는데
 * 둘 다 {@code measurement_attempt.status}를 그대로 낸 값이다.
 *
 * <h2>🔴 19차 R3 — 프론트가 기대한 3종과 다르다</h2>
 *
 * <p>{@code NOT_ASSIGNED · PENDING · COMPLETED}를 예상하셨는데 실제는 8종이고,
 * <b>"배정 없음"은 값이 아니라 {@code null}</b>이다 — REVIEW 응시 행이 없으면 LEFT JOIN이
 * NULL을 낸다.
 *
 * <p><b>3종으로 줄이지 않았다.</b> 줄이려면 서버가 8 → 3 매핑을 새로 정의해야 하는데 그 매핑은
 * 어디에도 근거가 없고, 한번 만들면 화면과 서버 양쪽에서 각자 자란다. 19차 R3에서 직접 쓰신
 * 표현대로 <b>불완전한 union은 다음에 나오는 값을 컴파일 타임에 거부</b>하므로 원장이 낼 수
 * 있는 값을 그대로 낸다.
 *
 * <p>화면이 세 갈래로 그리려면 이렇게 접으면 된다.
 *
 * <pre>
 * null        → 배정 없음(다시 보기 대상이 아니다)
 * COMPLETED   → 마침
 * 그 밖의 값    → 진행 중(아직 안 함 · 하는 중을 포함)
 * </pre>
 */
@Schema(name = "MeasurementAttemptStatus",
		description = """
				응시 진행 상태. 첫 응시(`initialAttemptStatus`)와 다시 보기(`reviewStatus`)가 같은 값 집합을 쓴다.

				⚠️ 다시 보기에서 **배정이 없으면 `null`** 이다 — "배정 없음"을 뜻하는 값은 따로 없다.

				`NOT_STARTED`(시작 전) · `SUBMITTED`(제출됨) · `ANALYZING`(분석 중) ·
				`SESSION_READY`(세션 열림) · `SESSION_IN_PROGRESS`(응시 중) · `COMPLETED`(완료) ·
				`FAILED`(실패) · `EXPIRED`(기한 초과)""",
		enumAsRef = true)
public enum MeasurementAttemptStatus {

	/** 응시가 만들어졌지만 아직 시작하지 않았다. */
	NOT_STARTED,

	/** 코드가 제출됐다. */
	SUBMITTED,

	/** 코드 분석 중이다. */
	ANALYZING,

	/** 분석이 끝나 세션을 열 수 있다. */
	SESSION_READY,

	/** 세션을 풀고 있다. */
	SESSION_IN_PROGRESS,

	/** 끝까지 마쳤다. */
	COMPLETED,

	/** 실패로 끝났다. */
	FAILED,

	/** 응시 창(다시 보기는 {@code review_due_at})이 지나 닫혔다. */
	EXPIRED
}
