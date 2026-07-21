package com.bigproject.backend.domain.cohort.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record EndCohortRequest(@NotBlank String reason) {
}
