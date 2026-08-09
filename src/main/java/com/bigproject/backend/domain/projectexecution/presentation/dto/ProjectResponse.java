package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ProjectSummary;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
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
        // 9차 Q1 ⓐ — 준비 상태는 서버가 판정한다. status(시간 축)와 별개인 구성 축이라 필드를 나눴다.
        // 화면의 4값은 `status === 'PLANNED' ? readiness : status` 한 줄로 만들어진다.
        ProjectReadiness readiness,
        @Schema(description = "시작일") LocalDate startDate,
        @Schema(description = "종료일. **날짜만이며 시각 의미가 없다** — 9차 Q2 참고", nullable = true) LocalDate endDate,

        // ── 9차 R1: 목록 화면이 셀마다 그리는 세 숫자 ────────────────────────────
        // 목록에 회차가 6~8건인데 화면이 직접 세려면 회차마다 교안·개념·후보를 따로 물어야 한다.
        // 목록 항목에 실어 주면 목록 조회 한 번으로 끝난다.
        @Schema(description = "연결된 교안 수. 0이면 화면의 `교안 연결 안 됨`", example = "2") int curriculumCount,
        @Schema(description = "확정된 검증 개념 수. 화면의 `검증 개념 2 / 3건`에서 분자", example = "3") int conceptCount,
        @Schema(description = "검증 개념 후보 수. 화면의 `후보 12건에서 3건`에서 앞 숫자", example = "12") int conceptCandidateCount
) {
    public static ProjectResponse from(ProjectSummary summary) {
        Project project = summary.project();
        return new ProjectResponse(
                project.getProjectId(),
                project.getCohortId(),
                project.getName(),
                project.getSequenceNo(),
                project.getProjectCategory(),
                project.getLifecycleStatus(),
                summary.readiness(),
                project.getStartDate(),
                project.getEndDate(),
                summary.curriculumCount(),
                summary.conceptCount(),
                summary.conceptCandidateCount()
        );
    }
}
