package com.bigproject.backend.domain.academicoperations.presentation.dto;

import java.util.List;

public record CohortListResponse(
		List<CohortResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
