package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "팀원 배정 요청")
public record AssignTeamMemberRequest(
        @Schema(description = "배정할 사람의 project_membership ID") @NotNull UUID projectMembershipId
) {
}