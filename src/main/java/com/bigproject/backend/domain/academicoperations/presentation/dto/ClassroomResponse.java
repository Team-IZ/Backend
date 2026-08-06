package com.bigproject.backend.domain.academicoperations.presentation.dto;

import com.bigproject.backend.domain.academicoperations.application.ClassroomService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "반 정보")
public record ClassroomResponse(
        @Schema(description = "반 ID") UUID classroomId,
        @Schema(description = "소속 기수 ID") UUID cohortId,
        @Schema(description = "반 이름", example = "1반") String name,
        @Schema(description = "담당 매니저 목록. 매니저가 하나도 없으면 빈 배열") List<Manager> managers,
        @Schema(description = "현재 이 반에 소속된 교육생 수") int traineeCount,
        @Schema(description = "담당 매니저가 없으면 true — 화면의 `매니저 미배정` 표시 근거") boolean managerAssignmentRequired
) {
    // 매니저 이름은 app_user 조인이 필요해 member 도메인 의존이 생기므로 여기서는 채우지 않음 (memberId만 제공)
    @Schema(description = "담당 매니저 한 명")
    public record Manager(
            @Schema(description = "매니저의 회원 ID(memberId)") UUID memberId,

            @Schema(description = "⚠ 아직 채워지지 않는 값 — 항상 빈 문자열이다. " +
                    "매니저 이름은 app_user 조인이 필요해 member 도메인 의존이 생기므로 채워주지 않는다.",
                    example = "")
            String name
    ) {
    }

    public static ClassroomResponse from(ClassroomService.ClassroomView view) {
        List<Manager> managers = view.managerUserIds().stream()
                .map(managerUserId -> new Manager(managerUserId, ""))
                .toList();
        return new ClassroomResponse(
                view.classroom().getClassId(),
                view.classroom().getCohortId(),
                view.classroom().getName(),
                managers,
                Math.toIntExact(view.traineeCount()),
                managers.isEmpty()
        );
    }
}