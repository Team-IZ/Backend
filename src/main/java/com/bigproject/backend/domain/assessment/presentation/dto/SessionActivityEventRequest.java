package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.ActivityEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 관찰 신호 이벤트 1건. {@link SessionActivityRequest}와 달리 <b>발생 시작 시각을 클라이언트가
 * 직접 싣는다</b> — 서버가 수신 시각에서 지속 시간만큼 거꾸로 근사하지 않는다.
 *
 * <p>어느 문제(질문)에 귀속되는지는 여전히 싣지 않는다 — {@link SessionActivityRequest}와 같은
 * 이유로 진행 위치는 서버 커서가 정본이다.
 */
@Schema(description = "응시 중 관찰 신호 이벤트 1건(창 이탈·연결 끊김·첫 타이핑 지연). 발생 시작 시각을 명시한다")
public record SessionActivityEventRequest(

		@Schema(description = "WINDOW_LEAVE · CONNECTION_LOSS · FIRST_KEYSTROKE_DELAY", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull
		ActivityEventType eventType,

		@Schema(description = "이 이벤트가 시작된 시각(클라이언트 실측)", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull
		Instant occurredAt,

		@Schema(description = "지속 시간(ms)", example = "3500", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull @Min(0) @Max(86_400_000)
		Integer durationMs
) {
}
