package com.bigproject.backend.domain.academicoperations.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record EndCohortRequest(@NotBlank String reason) {
}
