package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "교육생 반 배정 되돌리기 결과")
public record RollbackAssignmentResponse(
        @Schema(description = "실제로 배정이 해제된 교육생 ID 목록(중복 제거 후)") List<UUID> rolledBackTraineeIds,
        @Schema(description = "해제 인원 수") int rolledBackCount
) {
}