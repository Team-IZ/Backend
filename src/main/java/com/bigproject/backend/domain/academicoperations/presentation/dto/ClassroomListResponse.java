package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "기수 반 목록 응답")
public record ClassroomListResponse(
        @Schema(description = "그 기수에 편성된 반 전체. 하나도 없으면 빈 배열") List<ClassroomResponse> classrooms
) {
}