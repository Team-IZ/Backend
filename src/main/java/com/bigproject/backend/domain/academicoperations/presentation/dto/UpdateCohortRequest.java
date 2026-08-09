package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * 기수 이름·기간 부분 수정 요청(11차 Q2).
 *
 * <p>세 필드 모두 선택이며 <b>보낸 것만 바뀐다</b> — 반 수정({@code UpdateClassroomRequest})과 같은 규칙이라
 * 이름만 고쳐도 기간이 덮이지 않는다. 셋 다 생략하면 400 {@code COHORT_UPDATE_EMPTY}다.
 */
@Schema(description = "기수 수정 요청(부분 수정)")
public record UpdateCohortRequest(

        @Schema(description = "새 기수명. 같은 기관 안에서 중복되면 409. 생략하면 안 바꾼다", example = "7기", nullable = true)
        String name,

        @Schema(description = "새 시작일. 생략하면 안 바꾼다", example = "2026-03-02", nullable = true)
        LocalDate startDate,

        @Schema(description = "새 종료일. 생략하면 안 바꾼다. 결과가 시작일보다 빠르면 400", example = "2026-08-28", nullable = true)
        LocalDate endDate
) {
    /** 공백만 보낸 이름은 "안 바꾼다"와 같게 본다 — 빈 이름으로 덮어쓰는 사고를 막는다. */
    public String name() {
        return name == null || name.isBlank() ? null : name.trim();
    }
}
