package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "반 담당 매니저 전체 교체 요청")
public record UpdateClassroomManagersRequest(
        @Schema(description = "최종 담당 매니저 ID 목록. 보낸 목록이 그대로 최종 상태가 된다(부분 추가·삭제 아님). " +
                "중복은 서버가 제거하고, 빈 배열을 보내면 전체 해제된다. null은 허용하지 않는다(빈 배열로 보낼 것).")
        @NotNull List<UUID> managerIds
) {
}