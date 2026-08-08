package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "프로젝트 정보")
public record ProjectResponse(
        @Schema(description = "프로젝트 ID") UUID projectId,
        @Schema(description = "소속 기수 ID") UUID cohortId,
        @Schema(description = "프로젝트명") String name,
        @Schema(description = "기수 내 순번") Integer sequenceNo,
        // 값 목록은 공유 스키마(ProjectCategory·ProjectStatus)에 한 곳만 둔다. 여기에 description을
        // 적으면 $ref 형제 키가 되어 어차피 버려지므로, 설명도 enum 쪽에 적었다.
        ProjectCategory category,
        ProjectLifecycleStatus status,
        @Schema(description = "시작일") LocalDate startDate,
        @Schema(description = "종료일") LocalDate endDate
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getProjectId(),
                project.getCohortId(),
                project.getName(),
                project.getSequenceNo(),
                project.getProjectCategory(),
                project.getLifecycleStatus(),
                project.getStartDate(),
                project.getEndDate()
        );
    }
}