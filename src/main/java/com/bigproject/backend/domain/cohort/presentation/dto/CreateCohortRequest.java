package com.bigproject.backend.domain.cohort.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateCohortRequest(
		@NotNull UUID organizationId,
		@NotBlank String name,
		@NotNull LocalDate startDate,
		@NotNull LocalDate endDate,
		@NotBlank String educationTrack,
		@NotNull Integer cohortNo,
		@NotBlank String trackCode,
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

	public record InitialTrainee(
			@NotBlank String name,
			@NotBlank @Email String email
	) {
	}
}
