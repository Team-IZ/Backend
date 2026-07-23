package com.bigproject.backend.domain.member.presentation.dto;

import java.util.List;

public record TraineeListResponse(
		List<TraineeSummaryResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
	public TraineeListResponse {
		content = List.copyOf(content);
	}
}
