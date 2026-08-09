package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(description = "검증개념 확정 요청")
public record ConfirmConceptsRequest(
        @Schema(description = "확정할 매핑 ID 목록. 기존 활성 세트는 자동으로 교체(supersede)된다")
        @NotEmpty List<UUID> mappingIds
) {
}