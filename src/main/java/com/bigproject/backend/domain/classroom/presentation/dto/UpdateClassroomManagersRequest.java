package com.bigproject.backend.domain.classroom.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record UpdateClassroomManagersRequest(@NotNull List<UUID> managerIds) {
}
