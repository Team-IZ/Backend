package com.bigproject.backend.domain.cohort.presentation.dto;

import java.util.List;

public record CohortListResponse(
		List<CohortResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
