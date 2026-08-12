package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "프로젝트 일정 수정 요청")
public record UpdateProjectScheduleRequest(
        @Schema(description = "시작일") @NotNull LocalDate startDate,
        @Schema(description = "종료일") @NotNull LocalDate endDate,

        /*
         * 18차 R5 — 기간(날짜)과 마감(시각)을 나눈 ⓑ안이다.
         *
         * 종전에는 회차 기간이 DATE 뿐이라 "제출 마감이 몇 시인가"를 정할 자리가 없었다.
         * 화면에 시각 입력이 있었는데 서버가 저장하지 않아 **받아 놓고 버리는 입력**이 되어
         * 뺐다고 한다 — 운영자는 `23:59 마감`이라고 믿는데 그 시각이 아무 데도 없던 상태다.
         *
         * DB에는 project_assessment_round.submission_due_at 이 TIMESTAMPTZ NOT NULL 로
         * **이미 있다.** 없던 것은 그 값을 정할 입구였고, 이 필드가 그 입구다.
         */
        @Schema(description = """
                제출 마감 **시각**(ISO-8601, 예: `2026-08-18T14:59:00Z`).

                생략하면 **마감을 바꾸지 않는다** — 기간만 조정하는 경우가 흔해서 필수로 두지 않았다.
                기존 회차의 마감은 그대로 남는다.

                ⚠️ `endDate`(날짜)와 **서버가 자동으로 연결하지 않는다.** 기간을 늘려도 마감은
                움직이지 않으므로, 마감을 함께 옮기려면 이 값을 같이 보내야 한다. 자동 파생을
                넣지 않은 이유는 그렇게 하면 운영자가 기간만 손댔을 때 **학생에게 이미 알린
                마감이 조용히 바뀌기** 때문이다.

                시각대는 UTC로 저장된다. `23:59 KST` 마감은 `T14:59:00Z`다.""",
                example = "2026-08-18T14:59:00Z", nullable = true)
        Instant submissionDueAt
) {
}
