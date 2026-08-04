package com.bigproject.backend.domain.manager.presentation.dto;

import java.util.List;

public record TraineeRosterListResponse(
		List<TraineeRosterItemResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		TraineeRosterContextResponse context
) {
	public TraineeRosterListResponse {
		content = List.copyOf(content);
	}
}
