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
        // 만들 때는 필수로 받으면서 돌려주지는 않아, 화면이 보낸 값을 기억하는 수밖에 없었다 —
        // 새로고침하면 사라지고 다른 사람이 만든 반은 처음부터 몰랐다(9차 R6).
        @Schema(description = "정원. 반 카드의 `24 / 30명`에서 분모다. 배정 인원이 이 값을 넘어도 서버가 막지 않는다",
                example = "30") Integer capacity,
        @Schema(description = "담당 매니저 목록. 매니저가 하나도 없으면 빈 배열") List<Manager> managers,
        @Schema(description = "현재 이 반에 소속된 교육생 수") int traineeCount,
        @Schema(description = "담당 매니저가 없으면 true — 화면의 `매니저 미배정` 표시 근거") boolean managerAssignmentRequired
) {
    public static ClassroomResponse from(ClassroomService.ClassroomView view) {
        List<Manager> managers = view.managers().stream()
                .map(profile -> new Manager(profile.memberId(), profile.name(), profile.email()))
                .toList();
        return new ClassroomResponse(
                view.classroom().getClassId(),
                view.classroom().getCohortId(),
                view.classroom().getName(),
                view.classroom().getCapacity(),
                managers,
                Math.toIntExact(view.traineeCount()),
                managers.isEmpty()
        );
    }
}
