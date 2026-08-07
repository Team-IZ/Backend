package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "교육생 일괄 반 배정 결과")
public record AssignTraineesResponse(
        @Schema(description = "배정된 반 ID") UUID classroomId,
        @Schema(description = "실제 배정된 교육생 ID 목록(중복 제거 후)") List<UUID> assignedTraineeIds,
        @Schema(description = "배정 인원 수") int assignedCount
) {
}