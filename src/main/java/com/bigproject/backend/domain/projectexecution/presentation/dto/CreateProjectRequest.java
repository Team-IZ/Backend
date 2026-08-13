package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "프로젝트 생성 요청")
public record CreateProjectRequest(
        @Schema(description = "프로젝트명. 같은 기수 안에서 중복되면 409", example = "미프 3차")
        @NotBlank String name,

        // 응답과 같은 공유 스키마(ProjectCategory)를 참조한다 — 값 목록을 요청·응답에 따로 적어 두면
        // 한쪽에만 값이 늘었을 때 갈린다.
        @NotNull ProjectCategory category,

        @Schema(description = "시작일") @NotNull LocalDate startDate,
        @Schema(description = "종료일") @NotNull LocalDate endDate,

        /*
         * 22차 R5 ① — 생성 모달에서 시작·마감을 고르는 흐름인데 시각만 다른 화면(일정 수정)에서
         * 정해야 했다. 18차 R5로 일정 수정에는 열어 두고 생성에는 없던 자리다.
         *
         * 이 값이 갈 곳(project_assessment_round.submission_due_at)은 프로젝트를 만들 때 회차를
         * 함께 만들면서 채운다 — 22차 이전에는 회차 자체가 만들어지지 않아 저장할 자리가 없었다.
         */
        @Schema(description = """
                제출 마감 **시각**(ISO-8601, 예: `2026-08-18T14:59:00Z`).

                생략하면 **종료일의 23:59 KST**로 파생한다 — 기존 회차들이 그 규칙으로 들어가 있어
                화면이 이미 그렇게 읽는다. 시각대는 UTC로 저장되므로 `23:59 KST`는 `T14:59:00Z`다.

                ⚠️ 한 번 정해지면 `endDate`와 **다시 연결되지 않는다.** 기간을 늘려도 마감은 움직이지
                않으며, 함께 옮기려면 일정 수정(`PATCH .../schedule`)에서 마감을 같이 보내야 한다.
                학생에게 이미 알린 마감이 조용히 바뀌지 않게 하려는 것이다(18차 R5의 판단 그대로).""",
                example = "2026-08-18T14:59:00Z", nullable = true)
        Instant submissionDueAt
) {
}