package com.bigproject.backend.domain.organization.presentation.dto;

import java.util.List;

public record OrganizationListResponse(
		List<OrganizationResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
