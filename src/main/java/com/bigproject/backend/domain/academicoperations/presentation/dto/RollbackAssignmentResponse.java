package com.bigproject.backend.domain.academicoperations.presentation.dto;

import java.util.List;
import java.util.UUID;

public record RollbackAssignmentResponse(
        List<UUID> rolledBackTraineeIds,
        int rolledBackCount
) {
}
