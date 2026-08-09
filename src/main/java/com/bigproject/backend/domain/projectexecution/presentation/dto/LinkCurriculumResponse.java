package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LinkCurriculumResponse(
        UUID projectCurriculumId,
        UUID projectId,
        UUID curriculumVersionId,
        Integer sequenceNo,
        OffsetDateTime linkedAt
) {
    public static LinkCurriculumResponse from(ProjectCurriculum entity) {
        return new LinkCurriculumResponse(
                entity.getProjectCurriculumId(),
                entity.getProjectId(),
                entity.getCurriculumVersionId(),
                entity.getSequenceNo(),
                entity.getLinkedAt()
        );
    }
}