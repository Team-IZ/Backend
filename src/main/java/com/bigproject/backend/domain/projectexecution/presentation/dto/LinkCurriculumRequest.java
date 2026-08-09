package com.bigproject.backend.domain.projectexecution.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LinkCurriculumRequest(
        @NotNull(message = "curriculumVersionId는 필수입니다.")
        UUID curriculumVersionId
) {
}