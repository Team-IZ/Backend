package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "기수 목록 페이지 응답")
public record CohortListResponse(
		@Schema(description = "이 페이지의 기수 목록") List<CohortResponse> content,
		@Schema(description = "0부터 시작하는 현재 페이지 번호", example = "0") int page,
		@Schema(description = "페이지당 개수", example = "20") int size,
		@Schema(description = "전체 기수 수") long totalElements,
		@Schema(description = "전체 페이지 수") int totalPages
) {
}