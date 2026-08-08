package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "반 생성 요청")
public record CreateClassroomRequest(
        @Schema(description = "반 이름. 같은 기수 안에서 중복되면 409", example = "1반")
        @NotBlank String name,

        @Schema(description = "정원. 1 이상이어야 하며, 배정 인원이 이 값을 넘어도 서버가 막지는 않는다(표시용 값)", example = "20")
        @NotNull @Min(1) Integer capacity,

        @Schema(description = "담당 매니저로 지정할 사용자 ID 목록. ⚠ 지금은 서버가 사용하지 않는다 — " +
                "담당 매니저 지정은 PATCH .../managers로 별도 호출해야 한다. 생략하거나 빈 배열을 보내도 무방하다.", nullable = true)
        List<UUID> managerIds
) {
    public CreateClassroomRequest {
        managerIds = managerIds == null ? List.of() : List.copyOf(managerIds);
    }
}