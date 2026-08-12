package com.bigproject.backend.domain.analytics.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 계층이 달라도 모양이 같은 히트맵 응답이다.
 *
 * <p>{@code rows}는 언제나 <b>지금 계층의 비교 단위</b>(반·팀·팀원)이고, 고정된 상위 계층은
 * {@code scope}가 한 번만 싣는다. 셀에 소속 식별자를 반복하지 않으므로 계층이 바뀌어도
 * {@code null}로 비는 칸이 생기지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagerHeatmapResponse(
		UUID cohortId, UUID projectId, UUID assessmentRoundId,
		Level level, AttemptView attemptView, OffsetDateTime asOfAt,
		Scope scope, List<Concept> concepts, Row summary, List<Row> rows, Navigation navigation) {
	public enum Level { CLASS, TEAM, TRAINEE }
	public enum AttemptView { INITIAL, REVIEW }

	/** 이 조회에서 <b>고정된</b> 상위 계층이다. CLASS 계층은 고정 상위가 없어 {@code null}이다. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Scope(UUID classroomId, String classroomName, UUID teamId, String teamName) {
	}

	/**
	 * 가로축 한 칸이며 화면의 열 머리다.
	 *
	 * <p>{@code groupShortfall}이 열 머리 {@code ⚠}이고 판정 기준은 계층마다 다르다 —
	 * CLASS는 담당 반 전체, TEAM·TRAINEE는 {@code scope}의 그 반이다.
	 */
	public record Concept(int problemNo, UUID teachesId, String conceptName, Boolean groupShortfall) {
	}

	/**
	 * 세로축 한 줄이다. {@code rowId}는 계층에 따라 반·팀·교육생 식별자이며
	 * 합계 행({@code summary})에서는 {@code null}이다.
	 *
	 * <p>{@code memberCount}는 <b>명부 인원</b>이라 응시하지 않은 사람을 포함하며
	 * 셀의 {@code validCount}와 다르다. 개인 행은 인원 개념이 없어 {@code null}이다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Row(UUID rowId, String rowName, Integer memberCount, List<Cell> cells) {
	}

	/**
	 * 격자 한 칸이다.
	 *
	 * <p>{@code value}는 집계 행에서는 <b>평균</b>이고 개인 행에서는 <b>도달 단계 원값</b>(0~4)이다 —
	 * 개인까지 평균 내면 그 사람의 판정값이 흐려진다.
	 *
	 * <p>{@code groupShortfall}은 <b>반 행에만</b> 채운다. 팀은 3~4명이라 한 사람이 판정을
	 * 뒤집고, 개인은 집단 판정의 대상이 아니다.
	 *
	 * <p>{@code initialLevel}·{@code comparisonLevel}·{@code delta}는 {@code REVIEW}
	 * 전용이라 {@code INITIAL} 응답에서는 <b>키 자체가 빠진다</b>.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Cell(
			int problemNo, BigDecimal value, String status,
			Integer validCount, Integer notAttendedCount, Integer invalidCount, Integer interruptedCount,
			Boolean groupShortfall, Integer initialLevel, Integer comparisonLevel, Integer delta) {
	}

	/**
	 * 드릴다운 없이 계층을 바꾸기 위한 툴바 셀렉터 데이터다.
	 *
	 * <p>CLASS 계층에서는 {@code rows}가 곧 반 목록이라 <b>비운다</b> — 같은 값을 두 번 싣지 않는다.
	 */
	public record Navigation(List<Classroom> classrooms, List<Team> teams) {
	}

	public record Classroom(UUID classroomId, String classroomName, int memberCount) {
	}

	public record Team(UUID teamId, String teamName, int memberCount) {
	}
}
