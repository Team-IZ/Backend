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

        @Schema(description = "담당 매니저로 지정할 사용자 ID 목록이며 **반 생성과 같은 트랜잭션에서 배정된다**(11차 Q3). " +
                "생략하거나 빈 배열을 보내면 담당 없이 만들어지고, 나중에 PATCH .../managers로 바꿀 수 있다. " +
                "값은 ManagerRosterEntry.managerId와 같은 UUID다.", nullable = true)
        List<UUID> managerIds
) {
    public CreateClassroomRequest {
        managerIds = managerIds == null ? List.of() : List.copyOf(managerIds);
    }
}