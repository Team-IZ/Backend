package com.bigproject.backend.domain.cohort.presentation.dto;

import com.bigproject.backend.domain.cohort.domain.Cohort;
import com.bigproject.backend.domain.cohort.domain.CohortStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CohortResponse(
		UUID cohortId,
		UUID organizationId,
		String name,
		CohortStatus status,
		String educationTrack,
		LocalDate startDate,
		LocalDate endDate,
		int traineeCount,
		List<Manager> managers
) {
	// traineeCount/managers는 member·classroom 도메인이 준비되기 전까지 빈 값으로 채운다.
	public static CohortResponse from(Cohort cohort) {
		return new CohortResponse(
				cohort.getCohortId(),
				cohort.getOrgId(),
				cohort.getName(),
				cohort.getStatus(),
				cohort.getStage(),
				cohort.getStartDate(),
				cohort.getEndDate(),
				0,
				List.of()
		);
	}

	public record Manager(Long memberId, String name) {
	}
}
