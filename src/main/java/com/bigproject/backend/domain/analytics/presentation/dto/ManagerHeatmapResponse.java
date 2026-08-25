package com.bigproject.backend.domain.analytics.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

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
 *
 * <p><b>중첩 타입에 전부 {@code @Schema(name=)}을 붙인 이유</b>(30차 R2②) — springdoc은 중첩
 * record를 <b>단순 이름</b>으로 컴포넌트에 등록한다. 이 응답의 이름들({@code Concept} ·
 * {@code Cell} · {@code Row} · {@code Scope} · {@code Team} · {@code Classroom})은 스펙 전체에서
 * 가장 겹치기 쉬운 축에 속해, 이름만 두면 <b>나중에 등록된 쪽이 앞의 것을 조용히 덮어쓴다.</b>
 * 실제로 {@code Concept}·{@code Team}·{@code Classroom} 셋이 다른 도메인 DTO에 밀려 스펙에서
 * 사라졌고, 프론트는 히트맵 열 이름({@code conceptName})이 생성 타입에 아예 없는 상태로
 * 화면을 붙이지 못했다. 스키마가 문법적으로는 멀쩡해서 스펙을 읽어서는 보이지 않는다.
 */
public record ManagerHeatmapResponse(
		UUID cohortId, UUID projectId, UUID assessmentRoundId,
		Level level, AttemptView attemptView,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "집계 대상 셀이 하나도 없으면 키가 빠진다") OffsetDateTime asOfAt,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "CLASS 계층은 고정 상위가 없어 키가 빠진다") Scope scope,
		List<Concept> concepts,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		@Schema(description = "집계 대상 셀이 하나도 없으면 키가 빠진다") Row summary,
		List<Row> rows, Navigation navigation) {
	@Schema(name = "HeatmapLevel")
	public enum Level { CLASS, TEAM, TRAINEE }

	@Schema(name = "HeatmapAttemptView")
	public enum AttemptView { INITIAL, REVIEW }

	/** 이 조회에서 <b>고정된</b> 상위 계층이다. CLASS 계층은 고정 상위가 없어 {@code null}이다. */
	@Schema(name = "HeatmapScope")
	public record Scope(
			UUID classroomId, String classroomName,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "TEAM·CLASS 계층에서는 키가 빠진다") UUID teamId,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "TEAM·CLASS 계층에서는 키가 빠진다") String teamName) {
	}

	/**
	 * 가로축 한 칸이며 화면의 열 머리다.
	 *
	 * <p>{@code groupShortfall}이 열 머리 {@code ⚠}이고 판정 기준은 계층마다 다르다 —
	 * CLASS는 담당 반 전체, TEAM·TRAINEE는 {@code scope}의 그 반이다.
	 */
	@Schema(name = "HeatmapConcept")
	public record Concept(
			@Schema(description = """
					**격자 열 순번(1..N)**이며 열 순서 그대로다. 문제 순번이 아니다 — 문제 순번은
					팀 분석마다 다시 매겨져 회차 안에서 개념과 1:1이 아니다. 개념의 식별자는
					`teachesId`다.
					""", example = "1") int problemNo,
			UUID teachesId,
			@Schema(description = "화면의 **열 이름**이다", example = "API 응답 계약 설계") String conceptName,
			@Schema(description = """
					이 개념이 집단 미달이라 열 머리에 ⚠를 붙일지 여부다. 판정 기준 집단은 계층마다
					다르다 — CLASS는 담당 반 전체, TEAM·TRAINEE는 `scope`의 그 반이다.
					""")
			Boolean groupShortfall) {
	}

	/**
	 * 세로축 한 줄이다. {@code rowId}는 계층에 따라 반·팀·교육생 식별자이며
	 * 합계 행({@code summary})에서는 {@code null}이다.
	 *
	 * <p>{@code memberCount}는 <b>명부 인원</b>이라 응시하지 않은 사람을 포함하며
	 * 셀의 {@code validCount}와 다르다. 개인 행은 인원 개념이 없어 {@code null}이다.
	 */
	@Schema(name = "HeatmapRow")
	public record Row(
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "합계 행에서는 키가 빠진다") UUID rowId,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "합계 행에서는 키가 빠진다") String rowName,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "개인 행은 인원 개념이 없어 키가 빠진다") Integer memberCount,
			List<Cell> cells) {
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
	@Schema(name = "HeatmapCell")
	public record Cell(
			@Schema(description = "같은 열의 `concepts[].problemNo`와 짝이 되는 열 순번이다", example = "1")
			int problemNo,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "이 칸의 개념이다. `concepts[].teachesId`와 같은 값이다")
			UUID teachesId,
			BigDecimal value, String status,
			Integer validCount, Integer notAttendedCount, Integer invalidCount, Integer interruptedCount,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = """
					**명부에는 있는데 이 회차 격자에 자리가 없는 인원**이다(34차 R2). 합계 행에만 채운다.

					```
					memberCount = validCount + notAttendedCount + invalidCount
					            + interruptedCount + notInRoundCount
					```

					종전에는 이 자리가 없어 `memberCount 5`인데 세 카운터의 합이 4인 상태가 나왔고,
					화면이 차이를 설명할 근거가 없었다. 회차 중간 합류·이탈처럼 **수행 자체가
					만들어지지 않은** 사람이 여기 잡힌다 — 사유를 화면이 지어내지 않아도 되도록
					자리만 낸 것이고, 0이면 명부와 격자가 완전히 맞는다는 뜻이다.""")
			Integer notInRoundCount,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "반 행에만 채운다. 그 외에는 키가 빠진다") Boolean groupShortfall,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "REVIEW 전용. INITIAL 응답에서는 키가 빠진다") Integer initialLevel,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "REVIEW 전용. INITIAL 응답에서는 키가 빠진다") Integer comparisonLevel,
			@JsonInclude(JsonInclude.Include.NON_NULL)
			@Schema(description = "REVIEW 전용. INITIAL 응답에서는 키가 빠진다") Integer delta) {
	}

	/**
	 * 드릴다운 없이 계층을 바꾸기 위한 툴바 셀렉터 데이터다.
	 *
	 * <p>CLASS 계층에서는 {@code rows}가 곧 반 목록이라 <b>비운다</b> — 같은 값을 두 번 싣지 않는다.
	 */
	@Schema(name = "HeatmapNavigation", description = """
			계층을 바꾸는 툴바 셀렉터 데이터입니다.

			⚠️ **`level=CLASS`에서는 두 배열이 모두 빕니다**(의도된 동작입니다) — 그 계층에서는
			`rows[]`가 곧 반 목록이라 같은 값을 두 번 싣지 않습니다. 첫 화면의 반 셀렉터는
			`rows[]`로 만드시면 됩니다.

			`level=TEAM`이면 `classrooms`가 차고, `level=TRAINEE`면 `teams`까지 찹니다.
			""")
	public record Navigation(List<Classroom> classrooms, List<Team> teams) {
	}

	@Schema(name = "HeatmapClassroom")
	public record Classroom(UUID classroomId, String classroomName, int memberCount) {
	}

	@Schema(name = "HeatmapTeam")
	public record Team(UUID teamId, String teamName, int memberCount) {
	}
}
