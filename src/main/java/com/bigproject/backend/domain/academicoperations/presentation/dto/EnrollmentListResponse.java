package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "내 소속 기수·반 목록 응답")
public record EnrollmentListResponse(
        @Schema(description = "로그인한 사용자가 현재 유효하게 소속된 기수 전체. 소속이 없으면 빈 배열") List<EnrollmentResponse> enrollments
) {
}