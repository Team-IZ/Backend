package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 기수 생성 요청.
 *
 * <p>11차 Q3 — {@code initialTrainees}를 <b>스키마에서 뺐다.</b> "요청에 넣어도 저장되지 않는다"고
 * 적어 둔 채 남겨 두었더니 화면이 입력 칸을 만들었다가 지웠고, 생성 타입에 남아 있으면 다음 사람이
 * 다시 채워 넣는다. 교육생 등록은 행별 실패를 돌려줘야 해서({@code failures[]}) 기수 생성 응답에
 * 얹기에 맞지 않는다 — {@code POST /cohorts/{cohortId}/trainees}(CSV)와
 * {@code .../trainees/invitations}(직접 입력)가 그 자리다.
 */
@Schema(description = "기수 생성 요청")
public record CreateCohortRequest(
        @Schema(description = "기수를 개설할 기관 ID. 액세스 토큰의 기관과 다르면 403", example = "123e4567-e89b-12d3-a456-426614174000")
        @NotNull UUID organizationId,

        @Schema(description = "기수명. 같은 기관 안에서 중복되면 409", example = "7기")
        @NotBlank String name,

        @Schema(description = "기수 시작일. endDate보다 늦으면 400", example = "2026-03-02")
        @NotNull LocalDate startDate,

        @Schema(description = "기수 종료일. startDate보다 빠르면 400", example = "2026-08-28")
        @NotNull LocalDate endDate
) {
    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    @Schema(hidden = true)
    public boolean isValidPeriod() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
