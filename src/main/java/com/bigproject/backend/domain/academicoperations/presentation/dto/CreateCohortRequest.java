package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "기수 생성 요청")
public record CreateCohortRequest(
        @Schema(description = "기수를 개설할 기관 ID. 액세스 토큰의 기관과 다르면 403", example = "123e4567-e89b-12d3-a456-426614174000")
        @NotNull UUID organizationId,

        @Schema(description = "기수명. 같은 기관 안에서 중복되면 409", example = "7기")
        @NotBlank String name,

        @Schema(description = "기수 시작일. endDate보다 늦으면 400", example = "2026-03-02")
        @NotNull LocalDate startDate,

        @Schema(description = "기수 종료일. startDate보다 빠르면 400", example = "2026-08-28")
        @NotNull LocalDate endDate,

        @Schema(description = "⚠ 요청에 넣어도 저장되지 않는다. 교육생 등록은 POST /cohorts/{cohortId}/trainees(CSV) " +
                "또는 .../trainees/invitations(직접 입력)로 별도 처리한다.")
        List<@Valid InitialTrainee> initialTrainees
) {
    public CreateCohortRequest {
        initialTrainees = initialTrainees == null ? List.of() : List.copyOf(initialTrainees);
    }

    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    @Schema(hidden = true)
    public boolean isValidPeriod() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @Schema(description = "초기 교육생 한 명(⚠ 현재 서버가 사용하지 않음)")
    public record InitialTrainee(
            @Schema(description = "교육생 이름") @NotBlank String name,
            @Schema(description = "교육생 이메일") @NotBlank @Email String email
    ) {
    }
}