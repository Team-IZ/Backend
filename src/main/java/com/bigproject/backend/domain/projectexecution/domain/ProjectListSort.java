package com.bigproject.backend.domain.projectexecution.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 프로젝트 목록 정렬 기준(9차 R3). 화면 정렬 드롭다운의 세 항목과 1:1이다.
 *
 * <p>{@link #READINESS}의 판정 규칙을 서버에 둔 것은 <b>정렬을 서버가 하기로 한 이상 규칙도 서버에
 * 있어야 하기 때문</b>이다. 화면이 정렬하고 서버가 순서를 매기면 같은 규칙이 두 곳에 생긴다.
 * 다만 이 규칙은 9차 Q1의 답에 따라 달라질 수 있다 — Q1이 ⓐ(서버가 준비 상태를 판정해 내려준다)로
 * 정해지면 그 값으로 정렬하도록 바꾼다.
 */
@Schema(
		name = "ProjectListSort",
		description = """
				프로젝트 목록 정렬 기준.
				`READINESS`(준비 필요 순, 기본) · `DUE_SOON`(마감 임박 순) · `START_DATE`(시작 이른 순)""",
		enumAsRef = true
)
public enum ProjectListSort {

	/**
	 * 준비 필요 순. <b>덜 준비된 회차가 앞</b>이다 — 이 목록이 답하는 질문이 "뭐부터 손대야 하나"라서
	 * 기본값이다.
	 *
	 * <p>미충족 항목 수(교안 0건 · 확정 개념 0건 · 마감일 없음 셋 중 몇 개인지)가 많은 순,
	 * 같으면 마감이 이른 순, 그것도 같으면 최근 회차 순이다.
	 */
	READINESS,

	/** 마감 임박 순. 종료일 오름차순이며 종료일이 없는 회차가 맨 뒤다. */
	DUE_SOON,

	/** 시작 이른 순. 시작일 오름차순이며 시작일이 없는 회차가 맨 뒤다. */
	START_DATE
}
