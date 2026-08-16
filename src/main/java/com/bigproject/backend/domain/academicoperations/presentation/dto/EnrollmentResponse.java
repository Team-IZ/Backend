package com.bigproject.backend.domain.academicoperations.presentation.dto;

import com.bigproject.backend.domain.academicoperations.application.CohortService;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "사용자의 소속 기수 하나(현재 반 배정 포함)")
public record EnrollmentResponse(
        @Schema(description = "기수 ID") UUID cohortId,
        @Schema(description = "기수명", example = "7기") String cohortName,
        // 30차 R9 — 상태가 없어서 담당 기수가 둘인 매니저가 어느 쪽을 기본으로 열지 정할 근거가 없었다.
        @Schema(description = """
                기수 진행 상태입니다. 담당·소속 기수가 둘 이상일 때 **어느 기수를 기본으로 열지**
                고르는 근거입니다 — 오퍼레이터 화면과 같은 규칙(*진행 중을 고른다*)을 쓰시면 됩니다.

                💡 목록이 이미 그 순서로 옵니다(`enrollments[0]`이 곧 그 답입니다).
                """)
        CohortStatus status,
        @Schema(description = """
                현재 배정된 반이며 아직 배정 전이면 null입니다.

                ⚠️ **매니저에게는 언제나 null입니다.** 이 값의 원장은 교육생 배정(`class_membership`)이고
                매니저 배정은 `manager_assignment`라는 다른 원장입니다. 매니저의 담당 반은
                `GET /cohorts/{cohortId}/classrooms`가 담당 반만 돌려줍니다.
                """, nullable = true)
        Classroom classroom
) {
    // 서비스가 주는 EnrollmentView는 classId/className을 플랫하게 담고 있지만,
    // 응답에서는 하나의 classroom 객체로 묶어 null 여부 판단 지점을 한 곳으로 만든다.
    public static EnrollmentResponse from(CohortService.EnrollmentView view) {
        Classroom classroom = view.classId() == null
                ? null
                : new Classroom(view.classId(), view.className());
        return new EnrollmentResponse(view.cohortId(), view.cohortName(), view.status(), classroom);
    }

    @Schema(name = "EnrollmentClassroom", description = "현재 반 정보")
    public record Classroom(
            @Schema(description = "반 ID") UUID classroomId,
            @Schema(description = "반 이름", example = "1반") String name
    ) {
    }
}