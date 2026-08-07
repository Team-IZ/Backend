package com.bigproject.backend.domain.academicoperations.presentation.dto;

import com.bigproject.backend.domain.academicoperations.application.CohortService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "교육생 개인의 소속 기수 하나(현재 반 배정 포함)")
public record EnrollmentResponse(
        @Schema(description = "기수 ID") UUID cohortId,
        @Schema(description = "기수명", example = "7기") String cohortName,
        @Schema(description = "현재 배정된 반. 아직 배정 전이면 null") Classroom classroom
) {
    // 서비스가 주는 EnrollmentView는 classId/className을 플랫하게 담고 있지만,
    // 응답에서는 하나의 classroom 객체로 묶어 null 여부 판단 지점을 한 곳으로 만든다.
    public static EnrollmentResponse from(CohortService.EnrollmentView view) {
        Classroom classroom = view.classId() == null
                ? null
                : new Classroom(view.classId(), view.className());
        return new EnrollmentResponse(view.cohortId(), view.cohortName(), classroom);
    }

    @Schema(description = "현재 반 정보")
    public record Classroom(
            @Schema(description = "반 ID") UUID classroomId,
            @Schema(description = "반 이름", example = "1반") String name
    ) {
    }
}