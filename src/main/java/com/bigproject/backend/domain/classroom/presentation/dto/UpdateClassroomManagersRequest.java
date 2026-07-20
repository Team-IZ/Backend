package com.bigproject.backend.domain.classroom.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateClassroomManagersRequest(@NotNull List<Long> managerIds) {
}
