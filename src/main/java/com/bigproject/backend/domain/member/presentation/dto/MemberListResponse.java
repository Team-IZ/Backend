package com.bigproject.backend.domain.member.presentation.dto;

import java.util.List;

public record MemberListResponse(
		List<MemberSummaryResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {
}
