package com.bigproject.backend.domain.academicoperations.presentation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record RollbackAssignmentRequest(
        @NotEmpty List<UUID> traineeIds
) {
}