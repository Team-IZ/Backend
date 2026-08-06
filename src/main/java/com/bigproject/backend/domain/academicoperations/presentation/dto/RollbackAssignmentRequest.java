package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(description = "교육생 반 배정 되돌리기 요청")
public record RollbackAssignmentRequest(
        @Schema(description = "되돌릴 교육생의 사용자 ID(user_id) 목록. 1건 이상 필수. 중복은 서버가 제거한다. " +
                "그 기수 소속이 아니거나 현재 활성 배정이 없는(이미 무소속인) ID가 섞여 있으면 전체가 400으로 거절된다.")
        @NotEmpty List<UUID> traineeIds
) {
}