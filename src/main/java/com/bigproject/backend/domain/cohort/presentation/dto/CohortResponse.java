package com.bigproject.backend.domain.cohort.presentation.dto;

import com.bigproject.backend.domain.cohort.domain.CohortStatus;

import java.time.LocalDate;
import java.util.List;

public record CohortResponse(
		Long cohortId,
		Long organizationId,
		String name,
		CohortStatus status,
		String educationTrack,
		LocalDate startDate,
		LocalDate endDate,
		int traineeCount,
		List<Manager> managers
) {
	public record Manager(Long memberId, String name) {
	}
}
