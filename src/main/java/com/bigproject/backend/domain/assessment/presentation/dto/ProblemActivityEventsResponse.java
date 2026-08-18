package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 문제(질문) 하나의 관찰 신호 이벤트 집계. */
@Schema(description = "문제 하나의 관찰 신호 이벤트 집계")
public record ProblemActivityEventsResponse(

		UUID problemId,

		@Schema(description = "그 회차 안의 문제 번호(1~3)", example = "1")
		int problemNo,

		@Schema(description = "발생한 이벤트 타입별 집계. 한 건도 없던 타입은 나오지 않는다")
		List<ActivityEventSummary> events
) {

	@Schema(description = "이벤트 타입 하나의 집계")
	public record ActivityEventSummary(

			@Schema(description = "WINDOW_LEAVE · CONNECTION_LOSS · FIRST_KEYSTROKE_DELAY")
			String eventType,

			@Schema(description = "발생 횟수", example = "3")
			int count,

			@Schema(description = "지속 시간 합계(ms)", example = "45000")
			int totalDurationMs,

			@Schema(description = "발생 건 원본 목록. 발생 순")
			List<Occurrence> occurrences
	) {

		@Schema(description = "발생 건 하나")
		public record Occurrence(
				@Schema(description = "발생 시작 시각") Instant occurredAt,
				@Schema(description = "지속 시간(ms)", example = "3500") int durationMs) {
		}
	}
}
